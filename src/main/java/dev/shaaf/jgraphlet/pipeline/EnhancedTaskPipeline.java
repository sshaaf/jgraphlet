package dev.shaaf.jgraphlet.pipeline;

import dev.shaaf.jgraphlet.task.resource.ResourceAwareTask;
import dev.shaaf.jgraphlet.task.resource.ResourceConstraint;
import dev.shaaf.jgraphlet.task.resource.ResourceRequirements;
import dev.shaaf.jgraphlet.task.SplittableTask;
import dev.shaaf.jgraphlet.task.Task;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Enhanced version of TaskPipeline that supports advanced patterns like
 * fan-out/fan-in, dynamic task creation, and resource management.
 * 
 * This class extends the basic TaskPipeline functionality with:
 * - Dynamic task creation based on runtime conditions
 * - Fan-out patterns for parallel processing
 * - Fan-in patterns for result aggregation
 * - Resource-aware scheduling
 * - Work stealing and load balancing
 */
public class EnhancedTaskPipeline extends TaskPipeline {
    
    private final TaskPipelineConfig config;
    private final Map<String, FanOutConfig> fanOutConfigs = new ConcurrentHashMap<>();
    
    /**
     * Creates an EnhancedTaskPipeline with default configuration.
     */
    public EnhancedTaskPipeline() {
        this(TaskPipelineConfig.builder().build());
    }
    
    /**
     * Creates an EnhancedTaskPipeline with custom configuration.
     * 
     * @param config The pipeline configuration
     */
    public EnhancedTaskPipeline(TaskPipelineConfig config) {
        super(config.getExecutorService() != null ? 
              config.getExecutorService() : 
              Executors.newWorkStealingPool());
        this.config = config;
    }
    
    /**
     * Creates a fan-out configuration for parallel task execution.
     * 
     * @param taskName The name of the fan-out stage
     * @return A FanOutBuilder for configuring the fan-out behavior
     */
    public <I, O> FanOutBuilder<I, O> fanOut(String taskName) {
        return new FanOutBuilder<>(this, taskName);
    }
    
    /**
     * Creates a fan-in aggregation stage that collects results from parallel tasks.
     * 
     * @param taskName The name of the fan-in stage
     * @param aggregator The task that combines parallel results
     * @return This pipeline for method chaining
     */
    public <I, O> EnhancedTaskPipeline fanIn(String taskName, Task<List<I>, O> aggregator) {
        return (EnhancedTaskPipeline) super.add(taskName, aggregator);
    }
    
    /**
     * Configuration for fan-out behavior.
     */
    private static class FanOutConfig {
        final Function<Object, List<Task<?, ?>>> taskFactory;
        final int maxParallelism;
        final boolean loadBalancing;
        final boolean workStealing;
        
        FanOutConfig(Function<Object, List<Task<?, ?>>> taskFactory, 
                    int maxParallelism, boolean loadBalancing, boolean workStealing) {
            this.taskFactory = taskFactory;
            this.maxParallelism = maxParallelism;
            this.loadBalancing = loadBalancing;
            this.workStealing = workStealing;
        }
    }
    
    /**
     * Builder for configuring fan-out behavior.
     */
    public static class FanOutBuilder<I, O> {
        private final EnhancedTaskPipeline pipeline;
        private final String taskName;
        private Function<Object, List<Task<?, ?>>> taskFactory;
        private int maxParallelism = Runtime.getRuntime().availableProcessors();
        private boolean loadBalancing = false;
        private boolean workStealing = false;
        
        FanOutBuilder(EnhancedTaskPipeline pipeline, String taskName) {
            this.pipeline = pipeline;
            this.taskName = taskName;
        }
        
        /**
         * Sets a factory function that creates tasks dynamically based on input.
         * 
         * @param factory Function that creates tasks from input
         * @return This builder for method chaining
         */
        public FanOutBuilder<I, O> withTaskFactory(Function<Object, List<Task<?, ?>>> factory) {
            this.taskFactory = factory;
            return this;
        }
        
        /**
         * Sets the maximum parallelism for the fan-out stage.
         * 
         * @param maxParallelism Maximum number of parallel tasks
         * @return This builder for method chaining
         */
        public FanOutBuilder<I, O> withMaxParallelism(int maxParallelism) {
            this.maxParallelism = maxParallelism;
            return this;
        }
        
        /**
         * Enables load balancing for the fan-out stage.
         * 
         * @param loadBalancing Whether to enable load balancing
         * @return This builder for method chaining
         */
        public FanOutBuilder<I, O> withLoadBalancing(boolean loadBalancing) {
            this.loadBalancing = loadBalancing;
            return this;
        }
        
        /**
         * Enables work stealing for the fan-out stage.
         * 
         * @param workStealing Whether to enable work stealing
         * @return This builder for method chaining
         */
        public FanOutBuilder<I, O> withWorkStealing(boolean workStealing) {
            this.workStealing = workStealing;
            return this;
        }
        
        /**
         * Completes the fan-out configuration and returns the pipeline.
         * 
         * @param aggregatorName Name of the fan-in aggregator task
         * @param aggregator Task that combines results from parallel execution
         * @return The pipeline for method chaining
         */
        public EnhancedTaskPipeline fanIn(String aggregatorName, Task<List<Object>, O> aggregator) {
            // Store fan-out configuration
            FanOutConfig config = new FanOutConfig(taskFactory, maxParallelism, loadBalancing, workStealing);
            pipeline.fanOutConfigs.put(taskName, config);
            
            // Add a special fan-out task that handles the parallel execution
            FanOutTask fanOutTask = new FanOutTask(config);
            pipeline.add(taskName, fanOutTask);
            
            // Add the aggregator task
            return (EnhancedTaskPipeline) pipeline.add(aggregatorName, aggregator);
        }
    }
    
    /**
     * Internal task that handles fan-out execution.
     */
    private static class FanOutTask implements Task<Object, List<Object>> {
        private final FanOutConfig config;
        
        FanOutTask(FanOutConfig config) {
            this.config = config;
        }
        
        @Override
        public CompletableFuture<List<Object>> execute(Object input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Create parallel tasks using the factory
                    List<Task<?, ?>> parallelTasks = config.taskFactory.apply(input);
                    
                    // Limit parallelism if configured
                    if (parallelTasks.size() > config.maxParallelism) {
                        // TODO: Implement batching or queuing for excess tasks
                        parallelTasks = parallelTasks.subList(0, config.maxParallelism);
                    }
                    
                    // Execute tasks in parallel
                    List<CompletableFuture<Object>> futures = new ArrayList<>();
                    for (Task<?, ?> task : parallelTasks) {
                        @SuppressWarnings("unchecked")
                        Task<Object, Object> typedTask = (Task<Object, Object>) task;
                        CompletableFuture<Object> future = typedTask.execute(input, context);
                        futures.add(future);
                    }
                    
                    // Wait for all tasks to complete
                    CompletableFuture<Void> allComplete = CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture[0]));
                    
                    return allComplete.thenApply(v -> {
                        List<Object> results = new ArrayList<>();
                        for (CompletableFuture<Object> future : futures) {
                            try {
                                results.add(future.get());
                            } catch (Exception e) {
                                throw new RuntimeException("Parallel task failed", e);
                            }
                        }
                        return results;
                    }).get();
                    
                } catch (Exception e) {
                    throw new RuntimeException("Fan-out execution failed", e);
                }
            });
        }
    }
    
    /**
     * Adds a task with enhanced features like resource awareness.
     * 
     * @param taskName The name of the task
     * @param task The task to add
     * @return This pipeline for method chaining
     */
    @Override
    public EnhancedTaskPipeline add(String taskName, Task<?, ?> task) {
        // Check if task is resource-aware and configure accordingly
        if (task instanceof ResourceAwareTask && config.getResourceManager() != null) {
            // Wrap task with resource management
            task = new ResourceManagedTask<>(task, config.getResourceManager());
        }
        
        // Check if task is splittable and work stealing is enabled
        if (task instanceof SplittableTask && config.isWorkStealingEnabled()) {
            // Wrap task with work stealing support
            task = new WorkStealingTaskWrapper<>(task);
        }
        
        return (EnhancedTaskPipeline) super.add(taskName, task);
    }
    
    /**
     * Wrapper for resource-managed task execution.
     */
    private static class ResourceManagedTask<I, O> implements Task<I, O> {
        private final Task<I, O> delegate;
        private final TaskPipelineConfig.ResourceManager resourceManager;
        
        ResourceManagedTask(Task<I, O> delegate, TaskPipelineConfig.ResourceManager resourceManager) {
            this.delegate = delegate;
            this.resourceManager = resourceManager;
        }
        
        @Override
        public CompletableFuture<O> execute(I input, PipelineContext context) {
            if (delegate instanceof ResourceAwareTask) {
                ResourceAwareTask<I, O> resourceAware = (ResourceAwareTask<I, O>) delegate;
                ResourceRequirements requirements = resourceAware.estimateResources(input);
                
                // Check if resources are available
                if (!resourceManager.canSchedule(requirements)) {
                    // Notify task about resource constraints
                    ResourceConstraint constraint = resourceManager.getCurrentConstraints();
                    resourceAware.onResourceConstraint(constraint);
                }
                
                // Reserve resources
                resourceManager.reserveResources(requirements);
                
                try {
                    return delegate.execute(input, context).whenComplete((result, throwable) -> {
                        // Release resources when complete
                        resourceManager.releaseResources(requirements);
                    });
                } catch (Exception e) {
                    resourceManager.releaseResources(requirements);
                    throw e;
                }
            } else {
                return delegate.execute(input, context);
            }
        }
    }
    
    /**
     * Wrapper for work stealing task execution.
     */
    private static class WorkStealingTaskWrapper<I, O> implements Task<I, O> {
        private final Task<I, O> delegate;
        
        WorkStealingTaskWrapper(Task<I, O> delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public CompletableFuture<O> execute(I input, PipelineContext context) {
            if (delegate instanceof SplittableTask) {
                SplittableTask<I, O> splittable = (SplittableTask<I, O>) delegate;
                
                // Check if work should be split
                if (splittable.canSplit(input) && 
                    splittable.estimateWorkSize(input) >= splittable.getMinimumSplitSize()) {
                    
                    // Split the work
                    int targetParts = Math.min(splittable.getMaximumSplitParts(), 
                                              Runtime.getRuntime().availableProcessors());
                    List<SplittableTask<I, O>> splitTasks = splittable.split(input, targetParts);
                    
                    // Execute split tasks in parallel
                    List<CompletableFuture<O>> futures = new ArrayList<>();
                    for (SplittableTask<I, O> splitTask : splitTasks) {
                        futures.add(splitTask.execute(input, context));
                    }
                    
                    // Combine results
                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> {
                            List<O> results = futures.stream()
                                .map(CompletableFuture::join)
                                .toList();
                            return splittable.combineResults(results, context);
                        });
                }
            }
            
            return delegate.execute(input, context);
        }
    }
}
