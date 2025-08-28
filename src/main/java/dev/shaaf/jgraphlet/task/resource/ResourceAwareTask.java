package dev.shaaf.jgraphlet.task.resource;

import dev.shaaf.jgraphlet.task.Task;

/**
 * A task that can provide resource estimates and adapt to resource constraints.
 * This enables the pipeline to make intelligent scheduling decisions and
 * prevent resource starvation scenarios.
 * 
 * Tasks implementing this interface can:
 * 1. Provide upfront resource estimates for better scheduling
 * 2. Receive notifications about resource constraints
 * 3. Adapt their behavior when resources are limited
 *
 * @param <I> The input type
 * @param <O> The output type
 */
public interface ResourceAwareTask<I, O> extends Task<I, O> {
    
    /**
     * Estimates the resource requirements for processing the given input.
     * This method should be lightweight and fast, as it may be called
     * frequently by the pipeline scheduler.
     * 
     * @param input The input that will be processed
     * @return Estimated resource requirements
     */
    ResourceRequirements estimateResources(I input);
    
    /**
     * Called by the pipeline when resource constraints are detected.
     * Tasks can use this information to adapt their behavior, such as:
     * - Reducing memory usage by processing data in smaller chunks
     * - Decreasing parallelism when CPU is constrained
     * - Implementing buffering strategies when I/O is constrained
     * 
     * @param constraint Information about current resource constraints
     */
    void onResourceConstraint(ResourceConstraint constraint);
    
    /**
     * Indicates the minimum resources required for this task to function.
     * If these resources are not available, the task should not be scheduled.
     * 
     * @param input The input that will be processed
     * @return Minimum resource requirements
     */
    default ResourceRequirements getMinimumResources(I input) {
        return ResourceRequirements.minimal();
    }
    
    /**
     * Indicates whether this task can be delayed if resources are constrained.
     * Non-deferrable tasks (like those with time constraints) should return false.
     * 
     * @return true if the task can be delayed, false if it must execute immediately
     */
    default boolean isDeferrable() {
        return true;
    }
    
    /**
     * Called after successful task completion to report actual resource usage.
     * This helps the pipeline improve future resource estimates.
     * 
     * @param actualUsage The actual resources consumed during execution
     */
    default void reportActualUsage(ResourceRequirements actualUsage) {
        // Default implementation does nothing
    }
}
