package dev.shaaf.jgraphlet;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Async task pipeline that can be used to execute a chain of tasks in a defined order.
 * It supports parallel execution of independent tasks, caching, and complex dependencies.
 * 
 * <p>This class implements {@link AutoCloseable} to support try-with-resources for 
 * automatic cleanup of internal executor resources.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * try (TaskPipeline pipeline = new TaskPipeline()) {
 *     pipeline.add("task1", myTask)
 *             .then("task2", myOtherTask);
 *     String result = (String) pipeline.run("input").join();
 * } // Executor automatically cleaned up
 * }</pre>
 */
public class TaskPipeline implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(TaskPipeline.class.getName());

    private final Map<String, Task<?, ?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<String>> graph = new ConcurrentHashMap<>();
    private final Map<CacheKey, Object> cache = new ConcurrentHashMap<>();
    private final Map<CacheKey, CompletableFuture<Object>> futureCache = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final boolean ownedExecutor;

    private volatile String lastAddedTaskName;

    /**
     * Creates a TaskPipeline with an internally managed work-stealing executor.
     * Close the pipeline or call shutdown() when finished to release resources.
     */
    public TaskPipeline() {
        this.executor = Executors.newWorkStealingPool();
        this.ownedExecutor = true;
    }

    /**
     * Creates a TaskPipeline that uses the provided executor.
     * The caller remains responsible for shutting the executor down.
     *
     * @param executor the executor service to run tasks on
     */
    public TaskPipeline(ExecutorService executor) {
        this.executor = executor;
        this.ownedExecutor = false;
    }

    /**
     * Adds a task to the pipeline and makes it the end of a linear chain for the next {@code then()} call.
     *
     * @param taskName the unique name for the task node being added
     * @param task     the task instance to add
     * @return The pipeline instance for fluent chaining.
     * @throws IllegalArgumentException if a task with the same name has already been added
     */
    public TaskPipeline add(String taskName, Task<?, ?> task) {
        logger.log(Level.FINE, "Adding task {0} to the pipeline.", taskName);
        if (tasks.containsKey(taskName)) {
            throw new IllegalArgumentException("Task '" + taskName + "' has already been added.");
        }
        tasks.put(taskName, task);
        graph.put(taskName, new ArrayList<>());
        lastAddedTaskName = taskName;
        return this;
    }

    /**
     * Adds a task as a node to the graph without creating a connection.
     * Use this before creating connections with the {@link #connect(String, String)} method.
     *
     * @param taskName the unique name for the task node being added
     * @param task     the task instance to add
     * @return The pipeline instance for fluent chaining.
     * @throws IllegalArgumentException if a task with the same name has already been added
     */
    public TaskPipeline addTask(String taskName, Task<?, ?> task) {
        if (tasks.containsKey(taskName)) {
            throw new IllegalArgumentException("Task '" + taskName + "' has already been added.");
        }
        tasks.put(taskName, task);
        graph.put(taskName, new ArrayList<>());
        return this;
    }

    /**
     * Creates a linear dependency between the previously added task and the next task.
     *
     * @param nextTaskName the name for the next task to be added and connected
     * @param nextTask     the next task to execute
     * @return The pipeline instance for fluent chaining.
     * @throws IllegalStateException if called before {@link #add(String, Task)}
     */
    public TaskPipeline then(String nextTaskName, Task<?, ?> nextTask) {
        if (lastAddedTaskName == null) {
            throw new IllegalStateException("You must call 'add()' before calling 'then()'.");
        }
        addTask(nextTaskName, nextTask);
        String fromTaskName = this.lastAddedTaskName;
        connect(fromTaskName, nextTaskName);
        this.lastAddedTaskName = nextTaskName;
        return this;
    }

    /**
     * Creates an explicit dependency between any two tasks already added to the pipeline.
     *
     * @param fromTaskName the parent task name
     * @param toTaskName   the child task name that depends on the parent
     * @return The pipeline instance for fluent chaining.
     * @throws IllegalArgumentException if task names are null or identical
     * @throws IllegalStateException if either task has not been added yet
     */
    public TaskPipeline connect(String fromTaskName, String toTaskName) {

        if (toTaskName == null || fromTaskName == null) {
            throw new IllegalArgumentException("Task names cannot be null.");
        }

        if (fromTaskName.equals(toTaskName)) {
            throw new IllegalArgumentException("Cannot connect a task to itself: " + fromTaskName);
        }

        if (!tasks.containsKey(fromTaskName)) {
            throw new IllegalStateException("The 'from' task '" + fromTaskName + "' must be added to the pipeline before connecting from it.");
        }

        if (!tasks.containsKey(toTaskName)) {
            throw new IllegalStateException("The 'to' task '" + toTaskName + "' must be added to the pipeline before connecting to it.");
        }

        graph.get(fromTaskName).add(toTaskName);
        return this;
    }

    /**
     * Runs the pipeline asynchronously, executing tasks in the correct dependency order.
     *
     * @param initialInput The input for the first task(s) in the pipeline.
     * @return A CompletableFuture that will complete with the final result of the entire pipeline.
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Object> run(Object initialInput) {
        PipelineContext context = new PipelineContext();
        List<String> executionOrder = topologicalSort();
        Map<String, CompletableFuture<Object>> results = new HashMap<>();

        for (String taskName : executionOrder) {
            Task<Object, Object> currentTask = (Task<Object, Object>) tasks.get(taskName);
            List<String> predecessors = findPredecessorsFor(taskName);

            CompletableFuture<?>[] parentFutures = predecessors.stream()
                    .map(results::get)
                    .toArray(CompletableFuture[]::new);

            CompletableFuture<Void> allParentsDone = CompletableFuture.allOf(parentFutures);

            CompletableFuture<Object> currentFuture = allParentsDone.thenComposeAsync(v -> {
                Object input = gatherInputsFromCompletedParents(predecessors, results, initialInput);
                if (currentTask == null) {
                    throw new TaskRunException("Task '" + taskName + "' was not found in the pipeline.");
                }

                if (currentTask.isCacheable()) {
                    CacheKey cacheKey = new CacheKey(taskName, input);
                    
                    // Use computeIfAbsent to atomically check cache and compute if needed
                    return futureCache.computeIfAbsent(cacheKey, k -> {
                        logger.log(Level.FINE, "Executing task {0}, (cache miss).", taskName);
                        CompletableFuture<Object> taskResultFuture = currentTask.execute(input, context);
                        
                        // Populate the object cache when the future completes
                        return taskResultFuture.thenApply(result -> {
                            cache.put(cacheKey, result);
                            return result;
                        });
                    });
                } else {
                    logger.log(Level.FINE, "Executing task {0}, (cache miss).", taskName);
                    return currentTask.execute(input, context);
                }

            }, executor);

            results.put(taskName, currentFuture);
        }

        if (executionOrder.isEmpty()) {
            return CompletableFuture.completedFuture(initialInput);
        }

        String lastTaskName = executionOrder.get(executionOrder.size() - 1);
        return results.get(lastTaskName);
    }

    /**
     * Performs a topological sort on the task graph to determine the execution order.
     *
     * @return A list of task names in a valid execution order.
     */
    private List<String> topologicalSort() {
        Map<String, Integer> inDegree = new HashMap<>();
        for (String taskName : tasks.keySet()) {
            inDegree.put(taskName, 0);
        }
        for (List<String> successors : graph.values()) {
            for (String successor : successors) {
                inDegree.put(successor, inDegree.getOrDefault(successor, 0) + 1);
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> sortedOrder = new ArrayList<>();
        while (!queue.isEmpty()) {
            String u = queue.poll();
            sortedOrder.add(u);
            for (String v : graph.getOrDefault(u, Collections.emptyList())) {
                inDegree.put(v, inDegree.get(v) - 1);
                if (inDegree.get(v) == 0) {
                    queue.add(v);
                }
            }
        }

        if (sortedOrder.size() != tasks.size()) {
            throw new RuntimeException("Cycle detected in the pipeline graph. The process cannot complete." + sortedOrder);
        }
        return sortedOrder;
    }

    /**
     * Finds all direct parent tasks for a given task in the graph.
     *
     * @param taskName the task for which to find direct predecessors
     * @return a list of task names that directly precede the given task
     */
    private List<String> findPredecessorsFor(String taskName) {
        List<String> predecessors = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : graph.entrySet()) {
            if (entry.getValue().contains(taskName)) {
                predecessors.add(entry.getKey());
            }
        }
        return predecessors;
    }

    /**
     * Gathers inputs from completed parent tasks.
     * If there is one parent, it returns the raw result from that parent.
     * If there are multiple parents, it returns a Map<String, Object> of parent results.
     *
     * @param predecessors the list of parent task names
     * @param results      the map of task names to their futures
     * @param initialInput the initial pipeline input to use when there are no parents
     * @return the input object for the current task, either a single parent result, a map of results, or the initial input
     */
    private Object gatherInputsFromCompletedParents(List<String> predecessors, Map<String, CompletableFuture<Object>> results, Object initialInput) {
        if (predecessors.isEmpty()) {
            return initialInput;
        }

        if (predecessors.size() == 1) {
            return results.get(predecessors.get(0)).join(); // .join() is safe as this runs after parent completion.
        }

        return predecessors.stream()
                .collect(Collectors.toMap(
                        name -> name,
                        name -> results.get(name).join()
                ));
    }

    /**
     * Shuts down the internal executor if it was created by this TaskPipeline.
     * Call this method when you're done with the pipeline to prevent resource leaks.
     * 
     * Note: If you provided a custom executor via constructor, you are responsible 
     * for shutting it down yourself.
     */
    public void shutdown() {
        if (ownedExecutor && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    /**
     * Forcibly shuts down the internal executor if it was created by this TaskPipeline.
     * This may interrupt running tasks.
     */
    public void shutdownNow() {
        if (ownedExecutor && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    /**
     * Closes the TaskPipeline by gracefully shutting down the internal executor
     * if it was created by this instance. This method is called automatically
     * when using try-with-resources.
     * 
     * <p>This is equivalent to calling {@link #shutdown()}.</p>
     */
    @Override
    public void close() {
        shutdown();
    }
}