package dev.shaaf.jgraphlet.task;

import dev.shaaf.jgraphlet.pipeline.PipelineContext;

import java.util.List;

/**
 * A task that can be split into smaller units for work stealing and load balancing.
 * This enables better resource utilization when some threads become idle while
 * others are still processing large work units.
 * 
 * Splittable tasks are particularly useful for:
 * - Processing large collections where work units vary in size
 * - File processing where some files are much larger than others
 * - Any scenario where work distribution is uneven
 *
 * @param <I> The input type
 * @param <O> The output type
 */
public interface SplittableTask<I, O> extends Task<I, O> {
    
    /**
     * Checks if this task can be split into smaller units given the input.
     * This method should be lightweight as it may be called frequently
     * by the work stealing scheduler.
     * 
     * @param input The input that will be processed
     * @return true if the task can be split
     */
    boolean canSplit(I input);
    
    /**
     * Splits the work into smaller units for parallel execution.
     * Each returned task should handle a portion of the original work.
     * The sum of all split tasks should be equivalent to executing
     * the original task.
     * 
     * @param input The input to split
     * @param targetParts The suggested number of parts to split into
     * @return List of smaller tasks that collectively handle the input
     */
    List<SplittableTask<I, O>> split(I input, int targetParts);
    
    /**
     * Combines results from split tasks back into a single result.
     * This is called after all split tasks have completed successfully.
     * 
     * @param splitResults Results from all split tasks
     * @param context The pipeline context
     * @return Combined result
     */
    O combineResults(List<O> splitResults, PipelineContext context);
    
    /**
     * Estimates the work size for this task given the input.
     * This helps the scheduler make splitting decisions.
     * Larger values indicate more work.
     * 
     * @param input The input to be processed
     * @return Estimated work size (arbitrary units)
     */
    default long estimateWorkSize(I input) {
        return 1;
    }
    
    /**
     * Returns the minimum work size that justifies splitting.
     * Tasks smaller than this threshold should not be split further.
     * 
     * @return Minimum work size for splitting
     */
    default long getMinimumSplitSize() {
        return 2;
    }
    
    /**
     * Indicates the maximum number of parts this task should be split into.
     * This prevents excessive splitting that could hurt performance.
     * 
     * @return Maximum split parts, or -1 for no limit
     */
    default int getMaximumSplitParts() {
        return Runtime.getRuntime().availableProcessors() * 2;
    }
}
