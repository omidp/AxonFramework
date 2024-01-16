package org.axonframework.messaging.unitofwork;

import org.axonframework.common.FutureUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The UnitOfWork is a hook to phase tasks
 * TODO rename
 */
public class AsyncUnitOfWork implements ProcessingLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AsyncUnitOfWork.class);

    private final String identifier;
    private final UnitOfWorkProcessingContext context;

    public AsyncUnitOfWork() {
        this(UUID.randomUUID().toString());
    }

    public AsyncUnitOfWork(String identifier) {
        this(identifier, Runnable::run);
    }

    public AsyncUnitOfWork(String identifier, Executor workScheduler) {
        this.identifier = identifier;
        this.context = new UnitOfWorkProcessingContext(identifier, workScheduler);
    }

    @Override
    public String toString() {
        return "AsyncUnitOfWork{" + "id='" + identifier + '\'' + "phase='" + context.currentPhase.get() + '\'' + '}';
    }

    @Override
    public AsyncUnitOfWork on(Phase phase, Function<ProcessingContext, CompletableFuture<?>> action) {
        context.on(phase, action);
        return this;
    }

    @Override
    public ProcessingLifecycle onError(ErrorHandler action) {
        return context.onError(action);
    }

    @Override
    public ProcessingLifecycle whenComplete(Consumer<ProcessingContext> action) {
        return context.whenComplete(action);
    }

    /**
     * Executes all the registered handlers in their respective phases.
     *
     * @return a {@link CompletableFuture} that returns normally when the Unit Of Work has been committed or
     * exceptionally with the exception that caused the Unit of Work to have been rolled back.
     */
    public CompletableFuture<Void> execute() {
        return context.commit();
    }

    /**
     * Registers the given invocation for the {@link DefaultPhases#INVOCATION Invocation Phase} and executes the Unit of
     * Work. The return value of the invocation is returned when this Unit of Work is committed.
     *
     * @param invocation The handler to execute in the {@link DefaultPhases#INVOCATION Invocation Phase}
     * @param <R>        The type of return value returned by the invocation
     * @return a CompletableFuture that returns normally with the return value of the invocation when the Unit Of Work
     * has been committed or exceptionally with the exception that caused the Unit of Work to have been rolled back.
     */
    public <R> CompletableFuture<R> executeWithResult(Function<ProcessingContext, CompletableFuture<R>> invocation) {
        CompletableFuture<R> result = new CompletableFuture<>();
        onInvocation(p -> invocation.apply(p).whenComplete((r, e) -> {
            if (e == null) {
                result.complete(r);
            } else {
                result.completeExceptionally(e);
            }
        }));
        return execute().thenCombine(result, (executeResult, invocationResult) -> invocationResult);
    }

    private static class UnitOfWorkProcessingContext implements ProcessingContext {

        private final ConcurrentNavigableMap<Phase, Queue<Function<ProcessingContext, CompletableFuture<?>>>> phaseHandlers = new ConcurrentSkipListMap<>(
                Comparator.comparingInt(Phase::order));
        private final AtomicReference<Phase> currentPhase = new AtomicReference<>(null);
        private final LocalResources resources = new LocalResources();
        private final AtomicReference<Status> status = new AtomicReference<>(Status.NOT_STARTED);
        private final Queue<ErrorHandler> errorHandlers = new ConcurrentLinkedQueue<>();
        private final Queue<Consumer<ProcessingContext>> completeHandlers = new ConcurrentLinkedQueue<>();
        private final String name;
        private final Executor workScheduler;
        private final AtomicReference<CauseAndPhase> errorCause = new AtomicReference<>();

        public UnitOfWorkProcessingContext(String name, Executor workScheduler) {
            this.name = name;
            this.workScheduler = workScheduler;
        }

        @Override
        public Resources resources() {
            return resources;
        }

        @Override
        public boolean isStarted() {
            return status.get() != Status.NOT_STARTED;
        }

        @Override
        public boolean isError() {
            return status.get() == Status.COMPLETED_ERROR;
        }

        @Override
        public boolean isCommitted() {
            return status.get() == Status.COMPLETED;
        }

        @Override
        public boolean isCompleted() {
            Status currentStatus = status.get();
            return currentStatus == Status.COMPLETED
                    || currentStatus == Status.COMPLETED_ERROR;
        }

        @Override
        public ProcessingLifecycle on(Phase phase, Function<ProcessingContext, CompletableFuture<?>> action) {
            var p = currentPhase.get();
            if (p != null && phase.order() <= p.order()) {
                throw new IllegalStateException("Failed to register handler in phase " + phase + " (" + phase.order()
                                                        + "). ProcessingContext is already in phase " + p + " ("
                                                        + p.order() + ")");
            }
            phaseHandlers.computeIfAbsent(phase, k -> new ConcurrentLinkedQueue<>()).add(safe(phase, action));
            return this;
        }

        @Override
        public ProcessingLifecycle onError(ErrorHandler action) {
            ErrorHandler silentAction = failSilently(action);
            this.errorHandlers.add(silentAction);
            var p = status.get();
            if (p == Status.COMPLETED_ERROR && errorHandlers.remove(silentAction)) {
                // when in the completed phase, execute immediately
                // the removal attempt is to make sure that we aren't concurrently executing from the registering thread
                // as well as the thread that completed the processing context.
                CauseAndPhase causeAndPhase = errorCause.get();
                silentAction.handle(this, causeAndPhase.phase(), causeAndPhase.cause());
            }
            return this;
        }

        @Override
        public ProcessingLifecycle whenComplete(Consumer<ProcessingContext> action) {
            Consumer<ProcessingContext> silentAction = completeSilently(action);
            this.completeHandlers.add(silentAction);
            var p = status.get();
            if (p == Status.COMPLETED && completeHandlers.remove(silentAction)) {
                // when in the completed phase, execute immediately
                // the removal attempt is to make sure that we aren't concurrently executing from the registering thread
                // as well as the thread that completed the processing context.
                silentAction.accept(this);
            }
            return this;
        }

        private ErrorHandler failSilently(ErrorHandler action) {
            return (pc, ph, e) -> {
                try {
                    action.handle(pc, ph, e);
                } catch (Throwable ex) {
                    logger.warn("An onError handler threw an exception.", ex);
                }
            };
        }

        private Consumer<ProcessingContext> completeSilently(Consumer<ProcessingContext> action) {
            return p -> {
                try {
                    action.accept(p);
                } catch (Throwable e) {
                    logger.warn("A whenComplete handler threw an exception.", e);
                }
            };
        }

        /**
         * Wraps a given action to ensure exceptions are exclusively returned as a failed CompetableFuture and ensures
         * any exceptions or failures are registered in the processing context for the error handlers.
         *
         * @param phase  The original phase instance the handler is registered under
         * @param action The action to perform in this phase
         * @return a safe handler that doesn't throw unchecked exception
         */
        private Function<ProcessingContext, CompletableFuture<?>> safe(
                Phase phase, Function<ProcessingContext, CompletableFuture<?>> action) {
            return c -> {
                CompletableFuture<?> result;
                try {
                    result = action.apply(c);
                } catch (Exception e) {
                    result = CompletableFuture.failedFuture(e);
                }
                return result.exceptionallyCompose((e) -> {
                    errorCause.compareAndSet(null, new CauseAndPhase(phase, e));
                    logger.debug("A handler threw an exception in phase {}", phase, e);
                    return CompletableFuture.failedFuture(e);
                });
            };
        }

        public CompletableFuture<Void> commit() {
            if (!status.compareAndSet(Status.NOT_STARTED, Status.STARTED)) {
                throw new IllegalStateException("ProcessingContext cannot be committed (again)");
            }

            return executeAllPhaseHandlers()
                    .thenRun(this::runCompletionHandlers)
                    .exceptionallyCompose(this::invokeErrorHandlers);
        }

        private CompletableFuture<Void> executeAllPhaseHandlers() {
            if (phaseHandlers.isEmpty()) {
                // we're done
                return CompletableFuture.completedFuture(null);
            }
            // execute the next phase and run a new check once that phase is completed
            return runPhase(phaseHandlers.firstKey())
                    .thenCompose(r -> executeAllPhaseHandlers());
        }

        private void runCompletionHandlers() {
            status.set(Status.COMPLETED);
            while (!completeHandlers.isEmpty()) {
                Consumer<ProcessingContext> next = completeHandlers.poll();
                if (next != null) {
                    this.workScheduler.execute(() -> next.accept(this));
                }
            }
        }

        private CompletionStage<Void> invokeErrorHandlers(Throwable e) {
            CauseAndPhase recordedCause = errorCause.get();
            status.set(Status.COMPLETED_ERROR);
            while (!errorHandlers.isEmpty()) {
                ErrorHandler next = errorHandlers.poll();
                if (next != null) {
                    this.workScheduler.execute(() -> next.handle(this, recordedCause.phase(), recordedCause.cause()));
                }
            }
            return CompletableFuture.failedFuture(e);
        }

        private CompletableFuture<Void> runPhase(Phase phase) {
            currentPhase.set(phase);

            Queue<Function<ProcessingContext, CompletableFuture<?>>> handlers = phaseHandlers.remove(phase);
            if (handlers == null || handlers.isEmpty()) {
                logger.debug("Skipping phase {} (seq {}). No handlers registered", phase, phase.order());
                return CompletableFuture.completedFuture(null);
            }
            logger.debug("Calling {} handlers in phase {} (seq {}).", handlers.size(), phase, phase.order());

            return handlers.stream()
                           .map(handler -> CompletableFuture.completedFuture(null)
                                                            .thenComposeAsync(r -> handler.apply(this), workScheduler)
                                                            .thenAccept(FutureUtils::ignoreResult))
                           .reduce(CompletableFuture::allOf)
                           .orElseGet(FutureUtils::emptyCompletedFuture);
        }

        @Override
        public String toString() {
            return "UnitOfWorkProcessingContext{" + "name='" + name + '\'' + ", currentPhase=" + currentPhase.get()
                    + '}';
        }

        private enum Status {

            NOT_STARTED, STARTED, COMPLETED_ERROR, COMPLETED
        }

        private record CauseAndPhase(Phase phase, Throwable cause) {

        }
    }
}
