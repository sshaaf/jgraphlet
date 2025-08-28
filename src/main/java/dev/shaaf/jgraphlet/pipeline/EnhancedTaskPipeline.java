package dev.shaaf.jgraphlet.pipeline;

import dev.shaaf.jgraphlet.task.resource.ResourceAwareTask;
import dev.shaaf.jgraphlet.task.resource.ResourceConstraint;
import dev.shaaf.jgraphlet.task.resource.ResourceRequirements;
import dev.shaaf.jgraphlet.task.SplittableTask;
import dev.shaaf.jgraphlet.task.Task;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Map<String, FanOutBuilder<?, ?>> inProgressFanOuts = new ConcurrentHashMap<>();
    
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
              Executors.newWorkStealingPool()); // Create default executor if none provided
        this.config = config;
    }
    
    /**
     * Factory method for creating or retrieving a thread-safe FanOutBuilder.
     * Ensures that for any given fan-out task name, only one builder instance
     * is created and shared across threads.
     *
     * @param taskName The unique name for the fan-out task.
     * @return A thread-safe FanOutBuilder instance.
     */
    @SuppressWarnings("unchecked")
    public <I, O> FanOutBuilder<I, O> fanOut(String taskName) {
        // Atomically create and store the builder to prevent race conditions.
        // This ensures all threads get the same builder instance for the same name.
        return (FanOutBuilder<I, O>) inProgressFanOuts.computeIfAbsent(taskName,
                key -> new FanOutBuilder<>(this, key));
    }

    /**
     * Called by the FanOutBuilder to notify the pipeline that its definition
     * is complete and has been added to the task graph.
     *
     * @param taskName The name of the completed fan-out task.
     */
    void completeFanOut(String taskName) {
        inProgressFanOuts.remove(taskName);
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
        Function<Object, List<Task<?, ?>>> taskFactory;
        int maxParallelism;
        boolean loadBalancing;
        boolean workStealing;
        
        FanOutConfig() {
            this.maxParallelism = Runtime.getRuntime().availableProcessors();
            this.loadBalancing = false;
            this.workStealing = false;
        }
        
        FanOutConfig(Function<Object, List<Task<?, ?>>> taskFactory, 
                    int maxParallelism, boolean loadBalancing, boolean workStealing) {
            this.taskFactory = taskFactory;
            this.maxParallelism = maxParallelism;
            this.loadBalancing = loadBalancing;
            this.workStealing = workStealing;
        }
    }
    
    /**
     * A thread-safe builder for creating fan-out/fan-in patterns.
     * This class is now designed to be safely used by multiple threads to
     * define a single fan-out operation.
     */
    public static class FanOutBuilder<I, O> {
        private final EnhancedTaskPipeline pipeline;
        private final String taskName;
        private Function<Object, List<Task<?, ?>>> taskFactory;
        private FanOutConfig fanOutConfig = new FanOutConfig();

        FanOutBuilder(EnhancedTaskPipeline pipeline, String taskName) {
            this.pipeline = pipeline;
            this.taskName = taskName;
        }

        /**
         * Configures the factory function used to generate parallel tasks.
         * This method is thread-safe.
         *
         * @param factory A function that takes an input and returns a list of tasks to be executed in parallel.
         * @return This builder for method chaining.
         */
        public synchronized FanOutBuilder<I, O> withTaskFactory(Function<Object, List<Task<?, ?>>> factory) {
            this.taskFactory = factory;
            return this;
        }

        /**
         * Sets the maximum number of tasks to execute in parallel.
         * This method is thread-safe.
         *
         * @param maxParallelism The maximum degree of parallelism.
         * @return This builder for method chaining.
         */
        public synchronized FanOutBuilder<I, O> withMaxParallelism(int maxParallelism) {
            this.fanOutConfig.maxParallelism = maxParallelism;
            return this;
        }

        /**
         * Enables or disables load balancing for the fan-out tasks.
         * This method is thread-safe.
         *
         * @param enabled true to enable load balancing.
         * @return This builder for method chaining.
         */
        public synchronized FanOutBuilder<I, O> withLoadBalancing(boolean enabled) {
            this.fanOutConfig.loadBalancing = enabled;
            return this;
        }

        /**
         * Enables or disables work-stealing for the fan-out tasks.
         * This method is thread-safe.
         *
         * @param enabled true to enable work-stealing.
         * @return This builder for method chaining.
         */
        public synchronized FanOutBuilder<I, O> withWorkStealing(boolean enabled) {
            this.fanOutConfig.workStealing = enabled;
            return this;
        }
        
        /**
         * Finalizes the fan-out configuration and defines the fan-in task
         * that will aggregate the results. This method is thread-safe.
         *
         * @param fanInTaskName The name of the aggregator task.
         * @param aggregator    The task that will process the list of results from the fan-out tasks.
         * @return The pipeline for continued chaining.
         */
        public synchronized TaskPipeline fanIn(String fanInTaskName, Task<List<Object>, O> aggregator) {
            if (taskFactory == null) {
                throw new IllegalStateException("A task factory must be provided before defining the fan-in.");
            }

            // Use atomic check-and-set pattern to prevent race conditions
            try {
                // Create and add the single FanOutTask which will dynamically create child tasks.
                FanOutTask<I, O> fanOutTask = new FanOutTask<>(taskFactory, fanOutConfig);
                pipeline.add(taskName, fanOutTask);

                // The Aggregator task connects to the FanOutTask, creating the fan-in dependency.
                pipeline.add(fanInTaskName, aggregator);
                pipeline.connect(taskName, fanInTaskName);

                // Notify the pipeline that this fan-out definition is complete.
                pipeline.completeFanOut(taskName);

                return pipeline;
            } catch (IllegalArgumentException e) {
                // Another thread already added this task - check if it's our expected task
                if (e.getMessage().contains("has already been added") && pipeline.hasTask(taskName)) {
                    // Another thread successfully completed this fan-out definition
                    return pipeline;
                }
                // Re-throw if it's a different error
                throw e;
            }
        }
    }
    
    /**
     * Internal task that handles fan-out execution.
     */
    private static class FanOutTask<I, O> implements Task<I, List<O>> {
        private final Function<Object, List<Task<?, ?>>> taskFactory;
        private final FanOutConfig config;
        
        FanOutTask(Function<Object, List<Task<?, ?>>> taskFactory, FanOutConfig config) {
            this.taskFactory = taskFactory;
            this.config = config;
        }
        
        @Override
        public CompletableFuture<List<O>> execute(I input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Create parallel tasks using the factory
                    List<Task<?, ?>> parallelTasks = taskFactory.apply(input);
                    
                    // Limit parallelism if configured
                    if (parallelTasks.size() > config.maxParallelism) {
                        // TODO: Implement batching or queuing for excess tasks
                        parallelTasks = parallelTasks.subList(0, config.maxParallelism);
                    }
                    
                    // Execute tasks in parallel
                    List<CompletableFuture<O>> futures = new ArrayList<>();
                    for (Task<?, ?> task : parallelTasks) {
                        @SuppressWarnings("unchecked")
                        Task<I, O> typedTask = (Task<I, O>) task;
                        CompletableFuture<O> future = typedTask.execute(input, context);
                        futures.add(future);
                    }
                    
                    // Wait for all tasks to complete
                    CompletableFuture<Void> allComplete = CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture[0]));
                    
                    return allComplete.thenApply(v -> {
                        List<O> results = new ArrayList<>();
                        for (CompletableFuture<O> future : futures) {
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
     * Thread-safe wrapper for resource-managed task execution.
     * Uses atomic operations to prevent race conditions and resource leaks.
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
                
                // Use atomic flag to prevent double resource release
                AtomicBoolean resourcesReleased = new AtomicBoolean(false);
                
                // Atomic check-and-reserve operation
                if (!resourceManager.tryReserveResources(requirements)) {
                    // Resources not available - notify task about constraints
                    ResourceConstraint constraint = resourceManager.getCurrentConstraints();
                    resourceAware.onResourceConstraint(constraint);
                    
                    // Execute without resource reservation
                    return delegate.execute(input, context);
                }
                
                // Resources successfully reserved - ensure they're released exactly once
                return delegate.execute(input, context)
                    .whenComplete((result, throwable) -> {
                        // Safe resource release - only the first call will actually release
                        safeReleaseResources(requirements, resourcesReleased);
                    })
                    .exceptionally(throwable -> {
                        // Ensure resources are released even on exceptions
                        safeReleaseResources(requirements, resourcesReleased);
                        if (throwable instanceof RuntimeException) {
                            throw (RuntimeException) throwable;
                        }
                        throw new RuntimeException(throwable);
                    });
            } else {
                return delegate.execute(input, context);
            }
        }
        
        /**
         * Thread-safe resource release using atomic flag to prevent double-release.
         */
        private void safeReleaseResources(ResourceRequirements requirements, AtomicBoolean resourcesReleased) {
            if (resourcesReleased.compareAndSet(false, true)) {
                try {
                    if (resourceManager.safeReleaseResources(requirements)) {
                        // Resources successfully released
                    } else {
                        // Resources were already released or couldn't be released
                        // This is handled gracefully by the resource manager
                    }
                } catch (Exception e) {
                    // Log error but don't propagate to avoid masking original exceptions
                    // In a real implementation, this would use a logger
                    System.err.println("Warning: Failed to release resources: " + e.getMessage());
                }
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
