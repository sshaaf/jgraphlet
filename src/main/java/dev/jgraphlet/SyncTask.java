package dev.jgraphlet;

import java.util.concurrent.CompletableFuture;

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
