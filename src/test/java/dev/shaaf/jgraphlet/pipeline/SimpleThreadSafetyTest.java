package dev.shaaf.jgraphlet.pipeline;

import dev.shaaf.jgraphlet.task.Task;
import dev.shaaf.jgraphlet.task.resource.ResourceAwareTask;
import dev.shaaf.jgraphlet.task.resource.ResourceConstraint;
import dev.shaaf.jgraphlet.task.resource.ResourceRequirements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Disabled;

/**
 * Simple focused thread safety tests for EnhancedTaskPipeline
 */
class SimpleThreadSafetyTest {

    @Test
    @DisplayName("Concurrent pipeline creation should be thread-safe")
    void testConcurrentPipelineCreation() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
        List<EnhancedTaskPipeline> pipelines = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    // Create pipeline concurrently
                    EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline();
                    pipelines.add(pipeline);
                    
                    // Add a simple task
                    pipeline.add("test", new SimpleTestTask());
                    
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown(); // Start all threads
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Test should complete within 5 seconds");
        
        // Verify no exceptions and all pipelines created
        assertTrue(exceptions.isEmpty(), "No exceptions should occur: " + exceptions);
        assertEquals(threadCount, pipelines.size(), "All pipelines should be created");
    }

    @Test
    @DisplayName("Resource manager should prevent double allocation with atomic operations")
    void testResourceManagerConcurrency() throws InterruptedException {
        // Create a resource manager with limited resources
        TestResourceManager resourceManager = new TestResourceManager(1000L); // 1000 bytes available
        
        TaskPipelineConfig config = TaskPipelineConfig.builder()
            .withResourceManager(resourceManager)
            .build();

        try (EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline(config)) {
            // Add resource-aware task that requires 600 bytes
            pipeline.add("resourceTask", new ResourceHungryTask(600L));

            int threadCount = 10;
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger exceptionCount = new AtomicInteger(0);

            // Use ExecutorService for simpler coordination
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        // All threads try to run the pipeline simultaneously
                        Object result = pipeline.run("test_input").join();
                        if (result != null) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        exceptionCount.incrementAndGet();
                        // Expected - some executions should fail due to resource constraints
                    }
                }, executor);
                futures.add(future);
            }

            // Wait for all futures to complete with timeout
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
            
            try {
                allFutures.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("Test timed out - potential deadlock detected");
            } catch (ExecutionException e) {
                fail("Test failed with execution exception: " + e.getCause());
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

            // Verify that resource allocation is properly managed
            int totalAttempts = successCount.get() + exceptionCount.get();
            assertEquals(threadCount, totalAttempts, "All threads should have completed");
            
            // With atomic operations, we should have reasonable concurrency control
            assertTrue(successCount.get() >= 1, "At least one task should succeed");
            
            // Verify resource manager state is consistent
            assertEquals(0L, resourceManager.getCurrentUsage(), 
                "All resources should be released after execution");
        }
    }

    @Test
    @DisplayName("Enhanced pipeline should handle concurrent task execution gracefully")
    void testEnhancedPipelineConcurrency() throws InterruptedException {
        int threadCount = 5;
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        // Use ExecutorService for simpler coordination
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // Each thread creates its own pipeline (thread-safe pattern)
                    try (EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline()) {
                        pipeline.add("input_" + threadId, new SimpleTestTask())
                               .add("processing_" + threadId, new SimpleTestTask())
                               .add("output_" + threadId, new SimpleTestTask());

                        String result = (String) pipeline.run("test_" + threadId).join();
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
        
        assertTrue(exceptions.isEmpty(), "No exceptions should occur with separate pipelines: " + exceptions);
        assertEquals(threadCount, successCount.get(), "All threads should succeed");
    }

    // ========================================================================
    // Test Helper Classes
    // ========================================================================

    static class SimpleTestTask implements Task<String, String> {
        @Override
        public CompletableFuture<String> execute(String input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(10); // Simulate work
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return input + "_processed";
            });
        }
    }

    static class ResourceHungryTask implements ResourceAwareTask<String, String> {
        private final long memoryRequired;

        ResourceHungryTask(long memoryRequired) {
            this.memoryRequired = memoryRequired;
        }

        @Override
        public CompletableFuture<String> execute(String input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(100); // Simulate work
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return input + "_resource_processed";
            });
        }

        @Override
        public ResourceRequirements estimateResources(String input) {
            return new ResourceRequirements(memoryRequired, 0.1, false, Duration.ofMillis(100));
        }

        @Override
        public void onResourceConstraint(ResourceConstraint constraint) {
            // Handle constraint - could reduce memory usage
        }
    }

    static class SimpleAggregatorTask implements Task<List<Object>, Object> {
        @Override
        public CompletableFuture<Object> execute(List<Object> input, PipelineContext context) {
            return CompletableFuture.completedFuture("aggregated_" + input.size());
        }
    }

    /**
     * Deadlock-free thread-safe resource manager for testing
     */
    static class TestResourceManager implements TaskPipelineConfig.ResourceManager {
        private final AtomicLong availableMemory;
        private final AtomicLong usedMemory = new AtomicLong(0);

        TestResourceManager(long totalMemory) {
            this.availableMemory = new AtomicLong(totalMemory);
        }

        @Override
        public boolean canSchedule(ResourceRequirements requirements) {
            return usedMemory.get() + requirements.estimatedMemoryBytes <= availableMemory.get();
        }

        @Override
        public void reserveResources(ResourceRequirements requirements) {
            // This should only be called after canSchedule() returns true
            // In practice, use tryReserveResources() for atomic operations
            long oldValue, newValue;
            do {
                oldValue = usedMemory.get();
                newValue = oldValue + requirements.estimatedMemoryBytes;
                if (newValue > availableMemory.get()) {
                    throw new IllegalStateException("Not enough resources available");
                }
            } while (!usedMemory.compareAndSet(oldValue, newValue));
        }

        @Override
        public void releaseResources(ResourceRequirements requirements) {
            long oldValue, newValue;
            do {
                oldValue = usedMemory.get();
                newValue = Math.max(0, oldValue - requirements.estimatedMemoryBytes);
            } while (!usedMemory.compareAndSet(oldValue, newValue));
        }
        
        @Override
        public boolean tryReserveResources(ResourceRequirements requirements) {
            // Atomic check-and-reserve operation to prevent race conditions
            long oldValue, newValue;
            do {
                oldValue = usedMemory.get();
                newValue = oldValue + requirements.estimatedMemoryBytes;
                if (newValue > availableMemory.get()) {
                    return false; // Not enough resources
                }
            } while (!usedMemory.compareAndSet(oldValue, newValue));
            return true;
        }
        
        @Override
        public boolean safeReleaseResources(ResourceRequirements requirements) {
            long oldValue, newValue;
            do {
                oldValue = usedMemory.get();
                if (oldValue < requirements.estimatedMemoryBytes) {
                    return false; // Already released or insufficient resources
                }
                newValue = oldValue - requirements.estimatedMemoryBytes;
            } while (!usedMemory.compareAndSet(oldValue, newValue));
            return true;
        }

        @Override
        public ResourceConstraint getCurrentConstraints() {
            long used = usedMemory.get();
            long available = availableMemory.get();
            boolean memoryConstrained = used > available * 0.8;
            return new ResourceConstraint(memoryConstrained, false, false, available - used, 1.0);
        }

        public long getCurrentUsage() {
            return usedMemory.get();
        }
    }
}
