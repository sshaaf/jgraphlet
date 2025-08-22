package dev.shaaf.jgraphlet;

import java.util.concurrent.CompletableFuture;

/**
 * A convenience interface for tasks whose logic is synchronous.
 * Implementations provide their logic in {@link #executeSync(Object, PipelineContext)} and the
 * default {@link #execute(Object, PipelineContext)} implementation wraps it into a CompletableFuture.
 */
public interface SyncTask<I, O> extends Task<I, O> {

    /**
     * Executes the main synchronous logic of the task.
     * If this method throws an exception, it will be automatically caught and
     * propagated as a failed CompletableFuture.
     *
     * @param input   The input to the task.
     * @param context The shared pipeline context.
     * @return The output of the task.
     * @throws TaskRunException if the task logic fails.
     */
    O executeSync(I input, PipelineContext context) throws TaskRunException;

    /**
     * Executes this task asynchronously by invoking {@link #executeSync(Object, PipelineContext)}
     * on the calling thread and wrapping the result into a completed future. If the synchronous
     * execution throws, the returned future will be completed exceptionally with a TaskRunException.
     *
     * @param input   the input to the task
     * @param context the shared pipeline context
     * @return a future completing with the task result or exceptionally
     */
    @Override
    default CompletableFuture<O> execute(I input, PipelineContext context) {
        try {
            O result = executeSync(input, context);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new TaskRunException(e));
        }
    }
}
