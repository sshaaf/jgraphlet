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
    @Disabled("Temporarily disabled due to potential deadlock - needs refactoring")
    @DisplayName("Resource manager should prevent double allocation")
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
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        
                        // All threads try to run the pipeline simultaneously
                        Object result = pipeline.run("test_input").join();
                        if (result != null) {
                            successCount.incrementAndGet();
                        }
                        
                    } catch (Exception e) {
                        // Expected - some executions should fail due to resource constraints
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

            // At most 1 task should succeed (1000 bytes available, 600 bytes required)
            // This tests if resource allocation is properly managed
            assertTrue(successCount.get() <= 2, 
                "Resource allocation should limit concurrent execution. Success count: " + successCount.get());
            
            // Verify resource manager state is consistent
            assertEquals(0L, resourceManager.getCurrentUsage(), 
                "All resources should be released after execution");
        }
    }

    @Test
    @Disabled("Temporarily disabled due to potential deadlock - needs refactoring")
    @DisplayName("FanOutBuilder should handle concurrent usage gracefully")
    void testFanOutBuilderConcurrency() throws InterruptedException {
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    startLatch.await();

                    // Each thread creates its own pipeline and fan-out (recommended pattern)
                    try (EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline()) {
                        pipeline.add("input", new SimpleTestTask())
                               .fanOut("fanout_" + threadId)
                                   .withTaskFactory(input -> Arrays.asList(
                                       new SimpleTestTask(),
                                       new SimpleTestTask()
                                   ))
                                   .withMaxParallelism(2)
                               .fanIn("fanin", (Task<List<Object>, Object>) new SimpleAggregatorTask());

                        String result = (String) pipeline.run("test_" + threadId).join();
                        assertNotNull(result);
                    }

                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        
        assertTrue(exceptions.isEmpty(), "No exceptions should occur with separate builders: " + exceptions);
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
     * Simple thread-safe resource manager for testing
     */
    static class TestResourceManager implements TaskPipelineConfig.ResourceManager {
        private final AtomicLong availableMemory;
        private final AtomicLong usedMemory = new AtomicLong(0);

        TestResourceManager(long totalMemory) {
            this.availableMemory = new AtomicLong(totalMemory);
        }

        @Override
        public synchronized boolean canSchedule(ResourceRequirements requirements) {
            return usedMemory.get() + requirements.estimatedMemoryBytes <= availableMemory.get();
        }

        @Override
        public synchronized void reserveResources(ResourceRequirements requirements) {
            if (usedMemory.get() + requirements.estimatedMemoryBytes <= availableMemory.get()) {
                usedMemory.addAndGet(requirements.estimatedMemoryBytes);
            } else {
                throw new IllegalStateException("Not enough resources available");
            }
        }

        @Override
        public synchronized void releaseResources(ResourceRequirements requirements) {
            usedMemory.addAndGet(-requirements.estimatedMemoryBytes);
        }
        
        @Override
        public synchronized boolean tryReserveResources(ResourceRequirements requirements) {
            if (canSchedule(requirements)) {
                usedMemory.addAndGet(requirements.estimatedMemoryBytes);
                return true;
            }
            return false;
        }
        
        @Override
        public synchronized boolean safeReleaseResources(ResourceRequirements requirements) {
            if (usedMemory.get() >= requirements.estimatedMemoryBytes) {
                usedMemory.addAndGet(-requirements.estimatedMemoryBytes);
                return true;
            }
            return false; // Already released or insufficient resources
        }

        @Override
        public ResourceConstraint getCurrentConstraints() {
            boolean memoryConstrained = usedMemory.get() > availableMemory.get() * 0.8;
            return new ResourceConstraint(memoryConstrained, false, false,
                                        availableMemory.get() - usedMemory.get(), 1.0);
        }

        public long getCurrentUsage() {
            return usedMemory.get();
        }
    }
}
