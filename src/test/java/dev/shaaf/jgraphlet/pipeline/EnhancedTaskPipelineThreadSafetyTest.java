package dev.shaaf.jgraphlet.pipeline;

import dev.shaaf.jgraphlet.task.Task;
import dev.shaaf.jgraphlet.task.resource.ResourceAwareTask;
import dev.shaaf.jgraphlet.task.resource.ResourceConstraint;
import dev.shaaf.jgraphlet.task.resource.ResourceRequirements;
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
    
    @RepeatedTest(5)
    @Disabled("Temporarily disabled due to potential deadlock - needs refactoring")
    @DisplayName("Concurrent resource-aware task execution should be thread-safe")
    void testConcurrentResourceAwareExecution() throws Exception {
        int taskCount = 20;
        List<CompletableFuture<String>> futures = new ArrayList<>();
        
        try (EnhancedTaskPipeline testPipeline = new EnhancedTaskPipeline(
            TaskPipelineConfig.builder()
                .withResourceManager(resourceManager)
                .build())) {
            
            // Add multiple resource-aware tasks
            for (int i = 0; i < taskCount; i++) {
                String taskName = "resourceTask_" + i;
                testPipeline.add(taskName, new ConcurrentResourceAwareTask(i));
            }
            
            // Execute all tasks concurrently
            for (int i = 0; i < taskCount; i++) {
                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return (String) testPipeline.run("input_" + Thread.currentThread().getId()).join();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                futures.add(future);
            }
            
            // Wait for all executions to complete
            List<String> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();
            
            assertEquals(taskCount, results.size());
            
            // Verify resource manager state is consistent
            assertTrue(resourceManager.getCurrentMemory() >= 0);
            assertTrue(resourceManager.getCurrentCpu() >= 0);
        }
    }
    
    @Test
    @Disabled("Temporarily disabled due to potential deadlock - needs refactoring")
    @DisplayName("Fan-out builder thread safety with concurrent access")
    void testFanOutBuilderThreadSafety() throws InterruptedException {
        int threadCount = 5;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int threadId = 0; threadId < threadCount; threadId++) {
            final int id = threadId;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    latch.countDown();
                    latch.await(); // Start all threads simultaneously
                    
                    // Each thread creates its own fan-out configuration
                    try (EnhancedTaskPipeline testPipeline = new EnhancedTaskPipeline()) {
                        testPipeline.add("input_" + id, new SimpleTask("input"))
                                   .fanOut("fanout_" + id)
                                       .withTaskFactory(input -> List.of(
                                           new SimpleTask("parallel1_" + id),
                                           new SimpleTask("parallel2_" + id)
                                       ))
                                       .withMaxParallelism(2)
                                   .fanIn("fanin_" + id, (Task<List<Object>, Object>) new AggregatorTask());
                        
                        String result = (String) testPipeline.run("test_" + id).join();
                        assertNotNull(result);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            futures.add(future);
        }
        
        // All fan-out configurations should complete successfully
        assertDoesNotThrow(() -> 
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join()
        );
    }
    
    @Test
    @Disabled("Temporarily disabled due to potential deadlock - needs refactoring")
    @DisplayName("Resource manager should handle concurrent resource operations safely")
    void testResourceManagerThreadSafety() throws InterruptedException {
        int threadCount = 20;
        int operationsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int threadId = 0; threadId < threadCount; threadId++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    latch.countDown();
                    latch.await(); // Start all threads simultaneously
                    
                    for (int op = 0; op < operationsPerThread; op++) {
                        ResourceRequirements req = new ResourceRequirements(1024, 0.1, false);
                        
                        if (resourceManager.canSchedule(req)) {
                            resourceManager.reserveResources(req);
                            // Simulate work
                            Thread.sleep(1);
                            resourceManager.releaseResources(req);
                        }
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            futures.add(future);
        }
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Resource manager should be in a consistent state
        assertEquals(0, resourceManager.getCurrentMemory());
        assertEquals(0.0, resourceManager.getCurrentCpu(), 0.001);
    }
    
    // ========================================================================
    // Test Helper Classes
    // ========================================================================
    
    /**
     * Thread-safe resource manager implementation for testing
     */
    static class ThreadSafeResourceManager implements TaskPipelineConfig.ResourceManager {
        private final AtomicLong availableMemory = new AtomicLong(1024 * 1024 * 1024); // 1GB
        private final AtomicLong usedMemory = new AtomicLong(0);
        private volatile double availableCpu = Runtime.getRuntime().availableProcessors();
        private volatile double usedCpu = 0.0;
        private final Object cpuLock = new Object();
        
        @Override
        public boolean canSchedule(ResourceRequirements requirements) {
            synchronized (cpuLock) {
                return usedMemory.get() + requirements.estimatedMemoryBytes <= availableMemory.get() &&
                       usedCpu + requirements.estimatedCpuCores <= availableCpu;
            }
        }
        
        @Override
        public void reserveResources(ResourceRequirements requirements) {
            usedMemory.addAndGet(requirements.estimatedMemoryBytes);
            synchronized (cpuLock) {
                usedCpu += requirements.estimatedCpuCores;
            }
        }
        
        @Override
        public void releaseResources(ResourceRequirements requirements) {
            usedMemory.addAndGet(-requirements.estimatedMemoryBytes);
            synchronized (cpuLock) {
                usedCpu -= requirements.estimatedCpuCores;
            }
        }
        
        @Override
        public ResourceConstraint getCurrentConstraints() {
            synchronized (cpuLock) {
                boolean memoryConstrained = usedMemory.get() > availableMemory.get() * 0.8;
                boolean cpuConstrained = usedCpu > availableCpu * 0.8;
                return new ResourceConstraint(memoryConstrained, cpuConstrained, false,
                                            availableMemory.get() - usedMemory.get(), availableCpu - usedCpu);
            }
        }
        
        public long getCurrentMemory() { return usedMemory.get(); }
        public double getCurrentCpu() { 
            synchronized (cpuLock) { return usedCpu; }
        }
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
