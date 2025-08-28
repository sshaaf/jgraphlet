package dev.shaaf.jgraphlet.task;

import dev.shaaf.jgraphlet.pipeline.PipelineContext;

import java.util.List;

/**
 * A task that can create child tasks dynamically based on input data.
 * This enables processing patterns like file chunking where the number of
 * parallel tasks depends on runtime conditions (e.g., file size).
 * 
 * The pipeline will automatically execute child tasks in parallel and
 * collect their results for combination.
 *
 * @param <I> The input type
 * @param <O> The output type
 */
public interface DynamicTask<I, O> extends Task<I, O> {
    
    /**
     * Creates child tasks dynamically based on the input.
     * Each child task will be executed in parallel by the pipeline.
     * 
     * @param input The input data to analyze for child task creation
     * @param context The pipeline context for sharing state
     * @return List of child tasks to execute in parallel
     */
    List<Task<?, ?>> createChildren(I input, PipelineContext context);
    
    /**
     * Combines the results from all child tasks into the final output.
     * This method is called after all child tasks have completed successfully.
     * 
     * @param childResults The results from all child tasks
     * @param context The pipeline context
     * @return The combined result
     */
    O combineResults(List<Object> childResults, PipelineContext context);
    
    /**
     * Indicates the maximum number of child tasks that should be created.
     * This helps the pipeline manage resource usage.
     * 
     * @return Maximum number of child tasks, or -1 for no limit
     */
    default int getMaxChildren() {
        return -1; // No limit by default
    }
    
    /**
     * Indicates whether child tasks can be executed concurrently.
     * 
     * @return true if child tasks can run in parallel, false for sequential execution
     */
    default boolean allowConcurrentChildren() {
        return true;
    }
}
