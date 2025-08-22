package dev.shaaf.jgraphlet;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Aysnc task pipeline that can be used to execute a chain of tasks in a defined order.
 * It supports parallel execution of independent tasks, caching, and complex dependencies.
 */
public class TaskPipeline {

    private static final Logger logger = Logger.getLogger(TaskPipeline.class.getName());

    private final Map<String, Task<?, ?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<String>> graph = new ConcurrentHashMap<>();
    private final Map<CacheKey, Object> cache = new ConcurrentHashMap<>();
    private final Map<CacheKey, CompletableFuture<Object>> futureCache = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    private String lastAddedTaskName;

    public TaskPipeline() {
        this.executor = Executors.newCachedThreadPool();
    }

    public TaskPipeline(ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * Adds a task to the pipeline and makes it the end of a linear chain for the next `.then()` call.
     *
     * @param task The task to add.
     * @return The pipeline instance for fluent chaining.
     */
    public TaskPipeline add(String taskName, Task<?, ?> task) {
        logger.fine("ADDING: " + taskName);
        if (tasks.containsKey(taskName)) {
            throw new IllegalArgumentException("Task '" + taskName + "' has already been added.");
        }
        tasks.put(taskName, task);
        graph.put(taskName, new CopyOnWriteArrayList<>());
        lastAddedTaskName = taskName;
        return this;
    }

    /**
     * Adds a task as a node to the graph without creating a connection.
     * Use this before creating connections with the `connect()` method.
     *
     * @param task The task to add.
     * @return The pipeline instance for fluent chaining.
     */
    public TaskPipeline addTask(String taskName, Task<?, ?> task) {
        if (tasks.containsKey(taskName)) {
            throw new IllegalArgumentException("Task '" + taskName + "' has already been added.");
        }
        tasks.put(taskName, task);
        graph.put(taskName, new CopyOnWriteArrayList<>());
        return this;
    }

    /**
     * Creates a linear dependency between the previously added task and the next task.
     *
     * @param nextTask The task to execute next.
     * @return The pipeline instance for fluent chaining.
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
     * @param fromTaskName The parent task.
     * @param toTaskName   The child task that depends on the parent.
     * @return The pipeline instance for fluent chaining.
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
                        logger.fine("EXEC: Executing '" + taskName + "' (cache miss).");
                        CompletableFuture<Object> taskResultFuture = currentTask.execute(input, context);
                        
                        // Populate the object cache when the future completes
                        return taskResultFuture.thenApply(result -> {
                            cache.put(cacheKey, result);
                            return result;
                        });
                    });
                } else {
                    logger.fine("EXEC: Executing '" + taskName + "' (non-cacheable).");
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
}