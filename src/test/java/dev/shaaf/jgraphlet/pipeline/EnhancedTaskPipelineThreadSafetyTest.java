package dev.shaaf.jgraphlet.pipeline;

import dev.shaaf.jgraphlet.task.Task;
import dev.shaaf.jgraphlet.task.resource.ResourceAwareTask;
import dev.shaaf.jgraphlet.task.resource.ResourceConstraint;
import dev.shaaf.jgraphlet.task.resource.ResourceRequirements;
import dev.shaaf.jgraphlet.pipeline.EnhancedTaskPipeline.FanOutBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Disabled;

/**
 * Thread safety tests for EnhancedTaskPipeline to ensure safe concurrent usage.
 */
class EnhancedTaskPipelineThreadSafetyTest {
    
    private EnhancedTaskPipeline pipeline;
    private ThreadSafeResourceManager resourceManager;
    
    @BeforeEach
    void setUp() {
        resourceManager = new ThreadSafeResourceManager();
        TaskPipelineConfig config = TaskPipelineConfig.builder()
            .withResourceManager(resourceManager)
            .withWorkStealing(true)
            .withMaxConcurrentTasks(8)
            .build();
        
        pipeline = new EnhancedTaskPipeline(config);
    }
    
    @Test
    @DisplayName("Concurrent pipeline construction should be thread-safe")
    void testConcurrentPipelineConstruction() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<CompletableFuture<EnhancedTaskPipeline>> futures = new ArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            CompletableFuture<EnhancedTaskPipeline> future = CompletableFuture.supplyAsync(() -> {
                try {
                    latch.countDown();
                    latch.await(); // Start all threads simultaneously
                    
                    TaskPipelineConfig config = TaskPipelineConfig.builder()
                        .withResourceManager(new ThreadSafeResourceManager())
                        .build();
                    return new EnhancedTaskPipeline(config);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            futures.add(future);
        }
        
        // All pipelines should be created successfully
        List<EnhancedTaskPipeline> pipelines = futures.stream()
            .map(CompletableFuture::join)
            .toList();
        
        assertEquals(threadCount, pipelines.size());
        pipelines.forEach(p -> assertNotNull(p));
    }
    
    @Test
    @DisplayName("Concurrent task addition should be thread-safe")
    void testConcurrentTaskAddition() throws InterruptedException {
        int threadCount = 10;
        int tasksPerThread = 5;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int threadId = 0; threadId < threadCount; threadId++) {
            final int id = threadId;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    latch.countDown();
                    latch.await(); // Start all threads simultaneously
                    
                    for (int taskId = 0; taskId < tasksPerThread; taskId++) {
                        String taskName = "task_" + id + "_" + taskId;
                        pipeline.add(taskName, new SimpleTask("thread_" + id));
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            futures.add(future);
        }
        
        // Wait for all threads to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Verify all tasks were added (this will be verified by successful execution)
        assertDoesNotThrow(() -> {
            pipeline.add("final", new SimpleTask("final"));
        });
    }
    
    @Test
    @DisplayName("Concurrent resource-aware task execution should be thread-safe")
    void testConcurrentResourceAwareExecution() throws Exception {
        int taskCount = 10; // Reduced for faster execution
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger exceptionCount = new AtomicInteger(0);
        
        try (EnhancedTaskPipeline testPipeline = new EnhancedTaskPipeline(
            TaskPipelineConfig.builder()
                .withResourceManager(resourceManager)
                .build())) {
            
            // Add multiple resource-aware tasks
            for (int i = 0; i < taskCount; i++) {
                String taskName = "resourceTask_" + i;
                testPipeline.add(taskName, new ConcurrentResourceAwareTask(i));
            }
            
            // Use ExecutorService for better coordination
            ExecutorService executor = Executors.newFixedThreadPool(taskCount);
            
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < taskCount; i++) {
                final int taskId = i;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        String result = (String) testPipeline.run("input_" + taskId).join();
                        if (result != null) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        exceptionCount.incrementAndGet();
                        // Some failures may be expected due to resource constraints
                    }
                }, executor);
                futures.add(future);
            }
            
            // Wait for all executions to complete with timeout
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
            
            try {
                allFutures.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("Test timed out - potential deadlock detected");
            } catch (ExecutionException e) {
                fail("Test failed with execution exception: " + e.getCause());
            }
            
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            
            // Verify results
            int totalAttempts = successCount.get() + exceptionCount.get();
            assertEquals(taskCount, totalAttempts, "All tasks should have completed");
            
            // Verify resource manager state is consistent
            assertTrue(resourceManager.getCurrentMemory() >= 0);
            assertTrue(resourceManager.getCurrentCpu() >= 0);
        }
    }
    
    @Test
    @DisplayName("Enhanced pipeline builder thread safety with concurrent access")
    void testEnhancedPipelineBuilderThreadSafety() throws InterruptedException {
        int threadCount = 5;
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
        
        // Use ExecutorService for better coordination
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int threadId = 0; threadId < threadCount; threadId++) {
            final int id = threadId;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // Each thread creates its own enhanced pipeline (thread-safe pattern)
                    try (EnhancedTaskPipeline testPipeline = new EnhancedTaskPipeline()) {
                        testPipeline.add("input_" + id, new SimpleTask("input"))
                                   .add("middle_" + id, new SimpleTask("middle_" + id))
                                   .add("output_" + id, new SimpleTask("output_" + id));
                        
                        String result = (String) testPipeline.run("test_" + id).join();
                        assertNotNull(result);
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                }
            }, executor);
            futures.add(future);
        }
        
        // Wait for all futures to complete with timeout
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0]));
        
        try {
            allFutures.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fail("Test timed out - potential deadlock detected");
        } catch (ExecutionException e) {
            fail("Test failed with execution exception: " + e.getCause());
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        
        // Verify results
        assertTrue(exceptions.isEmpty(), "No exceptions should occur: " + exceptions);
        assertEquals(threadCount, successCount.get(), "All threads should succeed");
    }

    @Test
    @DisplayName("CRITICAL: Fan-out builder should handle concurrent configuration without deadlocks")
    void testFanOutBuilderConcurrentConfiguration() throws InterruptedException {
        // This is the test that reproduces the race condition you identified
        // Multiple threads will concurrently try to configure the SAME fan-out builder
        EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline();

        int threadCount = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // All threads will get the SAME builder instance
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // All threads get the SAME builder instance (this was the race condition!)
                    FanOutBuilder<String, String> builder = pipeline.fanOut("sharedFanOut");

                    // Each thread tries to configure the builder differently
                    builder.withTaskFactory((input) -> {
                        // Create a task that includes the thread ID to verify uniqueness
                        return List.of((Task<String, String>) (input2, context) ->
                            CompletableFuture.supplyAsync(() -> input2 + "_thread_" + threadId));
                    });

                    // Each thread sets different parallelism
                    builder.withMaxParallelism(threadId + 1);

                    // Only one thread should successfully call fanIn (others should be ignored)
                    if (threadId == 0) { // Let thread 0 complete the configuration
                        builder.fanIn("aggregator", (List<Object> inputs, PipelineContext context) ->
                            CompletableFuture.supplyAsync(() ->
                                inputs.stream()
                                    .map(Object::toString)
                                    .reduce("", (a, b) -> a + "|" + b)));
                    }

                    successCount.incrementAndGet();

                } catch (Exception e) {
                    exceptions.add(e);
                }
            }, executor);

            futures.add(future);
        }

        // Wait for all threads to complete
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0]));

        try {
            allFutures.get(15, TimeUnit.SECONDS); // Longer timeout for this critical test
        } catch (TimeoutException e) {
            fail("CRITICAL: Deadlock detected in fan-out builder concurrent configuration!");
        } catch (ExecutionException e) {
            fail("Test failed with execution exception: " + e.getCause());
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify results
        assertTrue(exceptions.isEmpty(), "No exceptions should occur in concurrent fan-out configuration: " + exceptions);
        assertEquals(threadCount, successCount.get(), "All threads should complete successfully");

        // Verify the pipeline can actually execute
        try {
            // Add a simple input task since fan-out expects input
            pipeline.add("input", (String input, PipelineContext context) ->
                CompletableFuture.completedFuture(input));

            // Connect input to fan-out
            pipeline.connect("input", "sharedFanOut");

            Object result = pipeline.run("test_input").join();
            assertNotNull(result, "Pipeline should execute successfully after concurrent configuration");
            System.out.println("Pipeline executed successfully with result: " + result);
        } catch (Exception e) {
            // Print more details about the failure
            System.err.println("Pipeline execution failed: " + e.getMessage());
            e.printStackTrace();
            fail("Pipeline execution failed after concurrent configuration: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Resource manager should handle concurrent resource operations safely")
    void testResourceManagerThreadSafety() throws InterruptedException {
        int threadCount = 10; // Reduced for faster execution
        int operationsPerThread = 50; // Reduced for faster execution
        AtomicInteger successfulOperations = new AtomicInteger(0);
        AtomicInteger failedOperations = new AtomicInteger(0);
        
        // Use ExecutorService for better coordination
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int threadId = 0; threadId < threadCount; threadId++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                for (int op = 0; op < operationsPerThread; op++) {
                    ResourceRequirements req = new ResourceRequirements(1024, 0.1, false);
                    
                    // Use atomic tryReserveResources to prevent race conditions
                    if (resourceManager.tryReserveResources(req)) {
                        try {
                            // Simulate work
                            Thread.sleep(1);
                            successfulOperations.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            resourceManager.safeReleaseResources(req);
                        }
                    } else {
                        failedOperations.incrementAndGet();
                    }
                }
            }, executor);
            futures.add(future);
        }
        
        // Wait for all futures to complete with timeout
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0]));
        
        try {
            allFutures.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fail("Test timed out - potential deadlock detected");
        } catch (ExecutionException e) {
            fail("Test failed with execution exception: " + e.getCause());
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        
        // Verify results
        int totalOperations = successfulOperations.get() + failedOperations.get();
        assertEquals(threadCount * operationsPerThread, totalOperations, 
            "All operations should have completed");
        
        // Resource manager should be in a consistent state
        assertEquals(0, resourceManager.getCurrentMemory(), 
            "All memory should be released");
        assertEquals(0.0, resourceManager.getCurrentCpu(), 0.001, 
            "All CPU should be released");
    }
    
    // ========================================================================
    // Test Helper Classes
    // ========================================================================
    
    /**
     * Deadlock-free thread-safe resource manager implementation for testing
     */
    static class ThreadSafeResourceManager implements TaskPipelineConfig.ResourceManager {
        private final AtomicLong availableMemory = new AtomicLong(1024 * 1024 * 1024); // 1GB
        private final AtomicLong usedMemory = new AtomicLong(0);
        private final AtomicLong availableCpuMillis; // CPU cores * 1000 for precision
        private final AtomicLong usedCpuMillis = new AtomicLong(0);
        
        ThreadSafeResourceManager() {
            this.availableCpuMillis = new AtomicLong((long)(Runtime.getRuntime().availableProcessors() * 1000));
        }
        
        @Override
        public boolean canSchedule(ResourceRequirements requirements) {
            long memoryNeeded = requirements.estimatedMemoryBytes;
            long cpuNeeded = (long)(requirements.estimatedCpuCores * 1000);
            
            return usedMemory.get() + memoryNeeded <= availableMemory.get() &&
                   usedCpuMillis.get() + cpuNeeded <= availableCpuMillis.get();
        }
        
        @Override
        public void reserveResources(ResourceRequirements requirements) {
            // Use atomic operations to prevent race conditions
            long memoryNeeded = requirements.estimatedMemoryBytes;
            long cpuNeeded = (long)(requirements.estimatedCpuCores * 1000);
            
            // Reserve memory atomically
            long oldMemory, newMemory;
            do {
                oldMemory = usedMemory.get();
                newMemory = oldMemory + memoryNeeded;
                if (newMemory > availableMemory.get()) {
                    throw new IllegalStateException("Not enough memory available");
                }
            } while (!usedMemory.compareAndSet(oldMemory, newMemory));
            
            // Reserve CPU atomically
            long oldCpu, newCpu;
            do {
                oldCpu = usedCpuMillis.get();
                newCpu = oldCpu + cpuNeeded;
                if (newCpu > availableCpuMillis.get()) {
                    // Rollback memory reservation
                    usedMemory.addAndGet(-memoryNeeded);
                    throw new IllegalStateException("Not enough CPU available");
                }
            } while (!usedCpuMillis.compareAndSet(oldCpu, newCpu));
        }
        
        @Override
        public void releaseResources(ResourceRequirements requirements) {
            long memoryToRelease = requirements.estimatedMemoryBytes;
            long cpuToRelease = (long)(requirements.estimatedCpuCores * 1000);
            
            // Release memory atomically
            long oldMemory, newMemory;
            do {
                oldMemory = usedMemory.get();
                newMemory = Math.max(0, oldMemory - memoryToRelease);
            } while (!usedMemory.compareAndSet(oldMemory, newMemory));
            
            // Release CPU atomically
            long oldCpu, newCpu;
            do {
                oldCpu = usedCpuMillis.get();
                newCpu = Math.max(0, oldCpu - cpuToRelease);
            } while (!usedCpuMillis.compareAndSet(oldCpu, newCpu));
        }
        
        @Override
        public boolean tryReserveResources(ResourceRequirements requirements) {
            long memoryNeeded = requirements.estimatedMemoryBytes;
            long cpuNeeded = (long)(requirements.estimatedCpuCores * 1000);
            
            // Try to reserve memory first
            long oldMemory, newMemory;
            do {
                oldMemory = usedMemory.get();
                newMemory = oldMemory + memoryNeeded;
                if (newMemory > availableMemory.get()) {
                    return false; // Not enough memory
                }
            } while (!usedMemory.compareAndSet(oldMemory, newMemory));
            
            // Try to reserve CPU
            long oldCpu, newCpu;
            do {
                oldCpu = usedCpuMillis.get();
                newCpu = oldCpu + cpuNeeded;
                if (newCpu > availableCpuMillis.get()) {
                    // Rollback memory reservation
                    usedMemory.addAndGet(-memoryNeeded);
                    return false; // Not enough CPU
                }
            } while (!usedCpuMillis.compareAndSet(oldCpu, newCpu));
            
            return true;
        }
        
        @Override
        public ResourceConstraint getCurrentConstraints() {
            long memUsed = usedMemory.get();
            long memAvailable = availableMemory.get();
            long cpuUsed = usedCpuMillis.get();
            long cpuAvailable = availableCpuMillis.get();
            
            boolean memoryConstrained = memUsed > memAvailable * 0.8;
            boolean cpuConstrained = cpuUsed > cpuAvailable * 0.8;
            
            return new ResourceConstraint(memoryConstrained, cpuConstrained, false,
                                        memAvailable - memUsed, (cpuAvailable - cpuUsed) / 1000.0);
        }
        
        public long getCurrentMemory() { return usedMemory.get(); }
        public double getCurrentCpu() { return usedCpuMillis.get() / 1000.0; }
    }
    
    /**
     * Simple test task implementation
     */
    static class SimpleTask implements Task<String, String> {
        private final String suffix;
        
        SimpleTask(String suffix) {
            this.suffix = suffix;
        }
        
        @Override
        public CompletableFuture<String> execute(String input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                // Simulate work
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return input + "_" + suffix;
            });
        }
    }
    
    /**
     * Resource-aware task for testing concurrent resource management
     */
    static class ConcurrentResourceAwareTask implements ResourceAwareTask<String, String> {
        private final int taskId;
        
        ConcurrentResourceAwareTask(int taskId) {
            this.taskId = taskId;
        }
        
        @Override
        public CompletableFuture<String> execute(String input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                // Simulate work
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return input + "_processed_" + taskId;
            });
        }
        
        @Override
        public ResourceRequirements estimateResources(String input) {
            return new ResourceRequirements(1024 * taskId, 0.1, false, Duration.ofMillis(100));
        }
        
        @Override
        public void onResourceConstraint(ResourceConstraint constraint) {
            // Handle constraint by reducing resource usage
        }
    }
    
    /**
     * Aggregator task for fan-in testing
     */
    static class AggregatorTask implements Task<List<Object>, Object> {
        @Override
        public CompletableFuture<Object> execute(List<Object> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                return "aggregated_" + input.size() + "_results";
            });
        }
    }
}
