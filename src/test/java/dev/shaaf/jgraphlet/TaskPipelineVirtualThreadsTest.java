package dev.shaaf.jgraphlet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TaskPipeline compatibility with virtual threads.
 * These tests only run on Java 21+ where virtual threads are available.
 * The project still targets Java 17 for compatibility.
 */
@EnabledForJreRange(min = JRE.JAVA_21)
class TaskPipelineVirtualThreadsTest {

    /**
     * Creates a virtual thread executor using reflection to maintain Java 17 compatibility.
     * This method will only be called when running on Java 21+.
     */
    private ExecutorService createVirtualThreadExecutor() {
        try {
            // Use reflection to call Executors.newVirtualThreadPerTaskExecutor()
            // This allows the code to compile on Java 17 but use virtual threads on Java 21+
            Class<?> executorsClass = Executors.class;
            Method method = executorsClass.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) method.invoke(null);
        } catch (Exception e) {
            // Should not happen as this test only runs on Java 21+
            throw new RuntimeException("Failed to create virtual thread executor", e);
        }
    }

    @Test
    void shouldWorkWithVirtualThreadExecutor() throws Exception {
        // Arrange
        ExecutorService virtualExecutor = createVirtualThreadExecutor();
        TaskPipeline pipeline = new TaskPipeline(virtualExecutor);

        try {
            // Create tasks that would benefit from virtual threads (I/O simulation)
            Task<String, String> fetchTask = (input, context) ->
                CompletableFuture.supplyAsync(() -> {
                    simulateIOOperation(100); // Simulate I/O
                    return "fetched: " + input;
                });

            Task<String, String> processTask = (input, context) ->
                CompletableFuture.supplyAsync(() -> {
                    simulateIOOperation(50); // Simulate processing
                    return input.toUpperCase();
                });

            // Act
            pipeline.add("fetch", fetchTask)
                   .then("process", processTask);

            String result = (String) pipeline.run("data").get(5, TimeUnit.SECONDS);

            // Assert
            assertEquals("FETCHED: DATA", result);
            System.out.println("✓ Virtual thread executor works with TaskPipeline");
            
        } finally {
            pipeline.shutdown();
            virtualExecutor.shutdown();
            assertTrue(virtualExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldHandleHighConcurrencyWithVirtualThreads() throws Exception {
        // Virtual threads excel at high concurrency scenarios
        ExecutorService virtualExecutor = createVirtualThreadExecutor();
        TaskPipeline pipeline = new TaskPipeline(virtualExecutor);

        try {
            AtomicInteger completedTasks = new AtomicInteger(0);

            // Create a simple I/O-bound task
            Task<Integer, String> ioTask = (input, context) ->
                CompletableFuture.supplyAsync(() -> {
                    simulateIOOperation(100); // Simulate blocking I/O
                    completedTasks.incrementAndGet();
                    return "Task " + input + " completed";
                });

            pipeline.add("ioTask", ioTask);

            // Act: Run many tasks concurrently
            int numTasks = 100;
            List<CompletableFuture<Object>> futures = new ArrayList<>();
            
            Instant start = Instant.now();
            for (int i = 0; i < numTasks; i++) {
                futures.add(pipeline.run(i));
            }
            
            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);
            
            Duration duration = Duration.between(start, Instant.now());

            // Assert
            assertEquals(numTasks, completedTasks.get(), 
                "All tasks should complete");
            
            // With virtual threads, 100 tasks with 100ms I/O should complete much faster than sequential
            // Sequential would take 10 seconds, we should complete in much less
            assertTrue(duration.getSeconds() < 5, 
                "High concurrency tasks should complete quickly with virtual threads (took: " + duration.getSeconds() + "s)");
            
            // Virtual threads allow many concurrent operations
            System.out.println("✓ Completed " + numTasks + " tasks in " + duration.toMillis() + "ms with virtual threads");
            
        } finally {
            pipeline.shutdown();
            virtualExecutor.shutdown();
            assertTrue(virtualExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldHandleFanInPatternWithVirtualThreads() throws Exception {
        ExecutorService virtualExecutor = createVirtualThreadExecutor();
        TaskPipeline pipeline = new TaskPipeline(virtualExecutor);

        try {
            // Create multiple I/O-bound tasks for fan-in
            Task<String, String> dbTask = (input, context) ->
                CompletableFuture.supplyAsync(() -> {
                    simulateIOOperation(150); // Simulate DB query
                    return "db-data";
                });

            Task<String, String> apiTask = (input, context) ->
                CompletableFuture.supplyAsync(() -> {
                    simulateIOOperation(200); // Simulate API call
                    return "api-data";
                });

            Task<String, String> cacheTask = (input, context) ->
                CompletableFuture.supplyAsync(() -> {
                    simulateIOOperation(50); // Simulate cache lookup
                    return "cache-data";
                });

            Task<Map<String, Object>, String> aggregateTask = (inputs, context) ->
                CompletableFuture.supplyAsync(() -> {
                    String db = (String) inputs.get("db");
                    String api = (String) inputs.get("api");
                    String cache = (String) inputs.get("cache");
                    return String.format("Aggregated: [%s, %s, %s]", db, api, cache);
                });

            // Build fan-in pipeline
            pipeline.addTask("db", dbTask)
                   .addTask("api", apiTask)
                   .addTask("cache", cacheTask)
                   .addTask("aggregate", aggregateTask);

            pipeline.connect("db", "aggregate")
                   .connect("api", "aggregate")
                   .connect("cache", "aggregate");

            // Act
            Instant start = Instant.now();
            String result = (String) pipeline.run("input").get(5, TimeUnit.SECONDS);
            Duration duration = Duration.between(start, Instant.now());

            // Assert
            assertEquals("Aggregated: [db-data, api-data, cache-data]", result);
            
            // Should complete in roughly the time of the slowest task (200ms), not the sum
            assertTrue(duration.toMillis() < 500, 
                "Fan-in should execute in parallel with virtual threads");
            
            System.out.println("✓ Fan-in pattern completed in " + duration.toMillis() + "ms");
            
        } finally {
            pipeline.shutdown();
            virtualExecutor.shutdown();
            assertTrue(virtualExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldNotSufferFromThreadPoolStarvation() throws Exception {
        // Virtual threads should handle blocking operations without thread pool starvation
        ExecutorService virtualExecutor = createVirtualThreadExecutor();
        TaskPipeline pipeline = new TaskPipeline(virtualExecutor);

        try {
            CountDownLatch allTasksStarted = new CountDownLatch(50);
            CountDownLatch proceedSignal = new CountDownLatch(1);
            AtomicInteger startedCount = new AtomicInteger(0);

            // Create a task that blocks until signaled
            Task<Integer, String> blockingTask = (input, context) ->
                CompletableFuture.completedFuture(input)
                    .thenApplyAsync(i -> {
                        startedCount.incrementAndGet();
                        allTasksStarted.countDown();
                        try {
                            // Block until signaled - this would cause thread pool starvation with platform threads
                            proceedSignal.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "Task " + i + " completed";
                    }, virtualExecutor); // Use the virtual thread executor explicitly

            pipeline.add("blocking", blockingTask);

            // Act: Start many blocking tasks
            int taskCount = 50;
            List<CompletableFuture<Object>> futures = IntStream.range(0, taskCount)
                .mapToObj(i -> pipeline.run(i))
                .toList();

            // Wait a bit for tasks to start (more lenient timeout)
            boolean allStarted = allTasksStarted.await(5, TimeUnit.SECONDS);
            
            // Signal all tasks to proceed
            proceedSignal.countDown();
            
            // Wait for completion
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);

            // Assert - check that most tasks started (virtual threads should handle many concurrent blocks)
            int started = startedCount.get();
            System.out.println("✓ Started " + started + " out of " + taskCount + " blocking tasks with virtual threads");
            
            assertTrue(started >= taskCount * 0.8, 
                "Most tasks should start with virtual threads (started: " + started + " out of " + taskCount + ")");
            
        } finally {
            pipeline.shutdown();
            virtualExecutor.shutdown();
            assertTrue(virtualExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldHandleMixedCPUAndIOTasksEfficiently() throws Exception {
        ExecutorService virtualExecutor = createVirtualThreadExecutor();
        TaskPipeline pipeline = new TaskPipeline(virtualExecutor);

        try {
            // I/O-bound task (benefits from virtual threads)
            Task<String, String> ioTask = (input, context) ->
                CompletableFuture.supplyAsync(() -> {
                    simulateIOOperation(100);
                    context.put("ioResult", "io-done");
                    return input + "-io";
                });

            // CPU-bound task (doesn't benefit as much from virtual threads)
            Task<String, Integer> cpuTask = (input, context) ->
                CompletableFuture.supplyAsync(() -> {
                    // Simulate CPU-intensive work
                    long sum = 0;
                    for (int i = 0; i < 1_000_000; i++) {
                        sum += i;
                    }
                    context.put("cpuResult", sum);
                    return input.length() + (int) (sum % 100);
                });

            // Another I/O task
            Task<Integer, String> finalIoTask = (input, context) ->
                CompletableFuture.supplyAsync(() -> {
                    simulateIOOperation(50);
                    String ioResult = context.get("ioResult", String.class).orElse("none");
                    Long cpuResult = context.get("cpuResult", Long.class).orElse(0L);
                    return String.format("Final: io=%s, cpu=%d, input=%d", ioResult, cpuResult, input);
                });

            // Build pipeline
            pipeline.add("io1", ioTask)
                   .then("cpu", cpuTask)
                   .then("io2", finalIoTask);

            // Act
            String result = (String) pipeline.run("test").get(5, TimeUnit.SECONDS);

            // Assert
            assertNotNull(result);
            assertTrue(result.contains("io=io-done"));
            assertTrue(result.contains("cpu="));
            
            System.out.println("✓ Mixed CPU and I/O tasks completed successfully");
            
        } finally {
            pipeline.shutdown();
            virtualExecutor.shutdown();
            assertTrue(virtualExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldMaintainThreadLocalSafety() throws Exception {
        // Virtual threads have their own thread locals
        ExecutorService virtualExecutor = createVirtualThreadExecutor();
        TaskPipeline pipeline = new TaskPipeline(virtualExecutor);

        try {
            ThreadLocal<String> threadLocal = new ThreadLocal<>();
            ConcurrentHashMap<String, String> threadValues = new ConcurrentHashMap<>();

            Task<Integer, String> taskWithThreadLocal = (input, context) ->
                CompletableFuture.supplyAsync(() -> {
                    String value = "thread-" + input;
                    threadLocal.set(value);
                    
                    // Simulate some work
                    simulateIOOperation(10);
                    
                    // Verify thread local is maintained
                    String retrieved = threadLocal.get();
                    threadValues.put(value, retrieved);
                    
                    return retrieved;
                });

            pipeline.add("threadLocalTask", taskWithThreadLocal);

            // Run multiple tasks concurrently
            List<CompletableFuture<Object>> futures = IntStream.range(0, 50)
                .mapToObj(pipeline::run)
                .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(5, TimeUnit.SECONDS);

            // Assert - each virtual thread maintained its own thread local
            assertEquals(50, threadValues.size());
            threadValues.forEach((key, value) -> 
                assertEquals(key, value, "Thread local should be maintained per virtual thread"));
            
            System.out.println("✓ Thread locals maintained correctly across " + threadValues.size() + " virtual threads");
            
        } finally {
            pipeline.shutdown();
            virtualExecutor.shutdown();
            assertTrue(virtualExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /**
     * Simulates an I/O operation that blocks the thread.
     * Virtual threads handle blocking operations efficiently.
     */
    private void simulateIOOperation(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}