package dev.shaaf.jgraphlet;

import java.util.concurrent.CompletableFuture;

/**
 * An asynchronous task that can be part of a pipeline.
 *
 * @param <I> The input type.
 * @param <O> The output type.
 */
public interface Task<I, O> {

    /**
     * Executes the main logic of the task asynchronously.
     * The method returns immediately with a promise of a future result.
     * Exceptions should be handled by completing the future exceptionally.
     *
     * @param input   the input to this task
     * @param context the shared pipeline context, useful for passing data across tasks
     * @return a future that will complete with the output of the task or complete exceptionally on failure
     */
    CompletableFuture<O> execute(I input, PipelineContext context);

    /**
     * Indicates whether the results of this task can be cached by the pipeline.
     * Implementations may override this to return true when the output is a pure
     * function of the input and context.
     *
     * @return true if the task is cacheable; false otherwise
     */
    default boolean isCacheable() {
        return false;
    }
}