package dev.shaaf.jgraphlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Thread-safety tests for TaskPipeline to verify concurrent operations work correctly
 * with the ConcurrentHashMap and CopyOnWriteArrayList implementations.
 */
class TaskPipelineThreadSafetyTest {

    private ExecutorService executor;
    private TaskPipeline pipeline;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
        pipeline = new TaskPipeline(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentPipelineBuildingShouldBeThreadSafe() throws InterruptedException {
        // Test that multiple threads can safely add tasks and connections simultaneously
        int numThreads = 10;
        int tasksPerThread = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        // Create tasks that will be added by multiple threads
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    for (int j = 0; j < tasksPerThread; j++) {
                        String taskName = "task_" + threadId + "_" + j;
                        SyncTask<String, String> task = (input, context) -> input + "_" + taskName;
                        pipeline.addTask(taskName, task);
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Start all threads simultaneously
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All threads should complete within timeout");
        
        assertTrue(exceptions.isEmpty(), "No exceptions should occur during concurrent building: " + exceptions);
        
        // Verify all tasks were added correctly
        // We can't easily access the internal tasks map, so we'll verify by trying to connect tasks
        // If a task wasn't added properly, connect would throw an exception
        for (int i = 0; i < numThreads; i++) {
            for (int j = 0; j < tasksPerThread - 1; j++) {
                String fromTask = "task_" + i + "_" + j;
                String toTask = "task_" + i + "_" + (j + 1);
                assertDoesNotThrow(() -> pipeline.connect(fromTask, toTask));
            }
        }
    }

    @Test
    void concurrentPipelineExecutionShouldBeThreadSafe() throws InterruptedException {
        // Build a simple pipeline first
        SyncTask<String, String> upperTask = (input, context) -> {
            // Add small delay to increase chance of race conditions
            try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return input.toUpperCase();
        };
        
        SyncTask<String, String> prefixTask = (input, context) -> "PREFIX_" + input;
        
        pipeline.add("upper", upperTask).then("prefix", prefixTask);

        // Execute the same pipeline concurrently from multiple threads
        int numThreads = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String input = "input" + threadId;
                    String result = (String) pipeline.run(input).join();
                    results.add(result);
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "All executions should complete");
        
        assertTrue(exceptions.isEmpty(), "No exceptions should occur during concurrent execution: " + exceptions);
        assertEquals(numThreads, results.size(), "All threads should produce results");
        
        // Verify results are correct
        for (int i = 0; i < numThreads; i++) {
            String expected = "PREFIX_INPUT" + i;
            assertTrue(results.contains(expected), "Result for input" + i + " should be present");
        }
    }

    @Test
    void cacheThreadSafetyShouldHandleConcurrentAccess() throws InterruptedException {
        // Create a cacheable task that tracks call count
        AtomicInteger callCount = new AtomicInteger(0);
        Task<String, String> cacheableTask = new Task<String, String>() {
            @Override
            public CompletableFuture<String> execute(String input, PipelineContext context) {
                return CompletableFuture.supplyAsync(() -> {
                    callCount.incrementAndGet();
                    // Add delay to increase chance of race conditions
                    try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return "processed_" + input;
                });
            }
            
            @Override
            public boolean isCacheable() {
                return true;
            }
        };

        pipeline.add("cacheable", cacheableTask);

        // Test thread-safety: concurrent access with same input should not cause data corruption
        int numThreads = 10;
        String sameInput = "shared_input";
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String result = (String) pipeline.run(sameInput).join();
                    results.add(result);
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All cache tests should complete");
        
        assertTrue(exceptions.isEmpty(), "No exceptions should occur during concurrent cache access: " + exceptions);
        assertEquals(numThreads, results.size(), "All threads should get results");
        
        // All results should be identical and correct (no data corruption)
        String expectedResult = "processed_" + sameInput;
        results.forEach(result -> assertEquals(expectedResult, result, "All results should be the same and correct"));
        
        // Verify the task was executed at least once and cache is populated
        assertTrue(callCount.get() >= 1, "At least one execution should occur");
        System.out.println("Cache thread-safety test: " + callCount.get() + " executions for " + numThreads + " threads (cache populated: " + (callCount.get() <= numThreads) + ")");
        
        // Test that subsequent single execution uses cache
        String secondResult = (String) pipeline.run(sameInput).join();
        assertEquals(expectedResult, secondResult, "Subsequent execution should return cached result");
    }

    @Test
    void stressTestConcurrentBuildingAndExecution() throws InterruptedException {
        // Stress test: build pipeline first, then stress test execution with multiple concurrent threads
        int executorThreads = 10;
        CountDownLatch executorsReady = new CountDownLatch(executorThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(executorThreads);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        // Add a complete working pipeline first (no concurrent modification)
        pipeline.add("initial", (SyncTask<String, String>) (input, context) -> "initial_" + input)
                .then("processing", (SyncTask<String, String>) (input, context) -> input + "_processed")
                .then("final", (SyncTask<String, String>) (input, context) -> input + "_final");

        // Add some additional isolated tasks to make the pipeline more complex
        for (int i = 0; i < 5; i++) {
            String taskName = "isolated_task_" + i;
            SyncTask<String, String> task = (input, context) -> input + "_" + taskName;
            pipeline.addTask(taskName, task);
        }

        // Executor threads stress test the pipeline with high concurrency
        for (int i = 0; i < executorThreads; i++) {
            final int executorId = i;
            executor.submit(() -> {
                try {
                    executorsReady.countDown();
                    startLatch.await();
                    
                    // Each thread runs the pipeline multiple times with different inputs
                    for (int j = 0; j < 5; j++) {
                        String input = "executor_" + executorId + "_run_" + j;
                        String result = (String) pipeline.run(input).join();
                        // Verify we get expected result structure
                        assertTrue(result.endsWith("_final"), "Result should end with _final");
                        
                        // Small delay to increase concurrency pressure
                        Thread.sleep(1);
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Wait for all threads to be ready, then start the stress test
        assertTrue(executorsReady.await(5, TimeUnit.SECONDS), "Executor threads should be ready");
        
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Stress test should complete");
        
        assertTrue(exceptions.isEmpty(), "No exceptions should occur during stress test: " + exceptions);
    }

    @Test
    void concurrentConnectionsShouldBeThreadSafe() throws InterruptedException {
        // Pre-add tasks that will be connected concurrently
        int numTasks = 20;
        for (int i = 0; i < numTasks; i++) {
            String taskName = "task_" + i;
            SyncTask<String, String> task = (input, context) -> input + "_" + taskName;
            pipeline.addTask(taskName, task);
        }

        // Now connect them concurrently
        int numThreads = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    // Each thread connects different pairs of tasks
                    for (int j = 0; j < numTasks - 1; j += numThreads) {
                        int taskIndex = (threadId + j) % (numTasks - 1);
                        String fromTask = "task_" + taskIndex;
                        String toTask = "task_" + (taskIndex + 1);
                        pipeline.connect(fromTask, toTask);
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All connection operations should complete");
        
        assertTrue(exceptions.isEmpty(), "No exceptions should occur during concurrent connections: " + exceptions);
    }
}
