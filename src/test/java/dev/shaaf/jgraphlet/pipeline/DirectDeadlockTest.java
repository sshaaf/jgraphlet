package dev.shaaf.jgraphlet.pipeline;

import dev.shaaf.jgraphlet.task.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * More direct test to trigger the ReadWriteLock deadlock scenario
 */
class DirectDeadlockTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Direct test: ReadWriteLock upgrade deadlock in TaskPipeline")
    void testDirectReadWriteLockDeadlock() throws Exception {
        TaskPipeline pipeline = new TaskPipeline();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch attemptAdd = new CountDownLatch(1);
        AtomicBoolean deadlockDetected = new AtomicBoolean(false);
        
        // Create a task that will block and try to modify pipeline
        Task<String, String> blockingTask = (input, context) -> {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    taskStarted.countDown(); // Signal that task has started
                    attemptAdd.await(5, TimeUnit.SECONDS); // Wait for signal to attempt add
                    
                    // This should cause deadlock: trying to acquire writeLock while readLock is held
                    pipeline.add("deadlockTask", (input2, context2) -> 
                        CompletableFuture.completedFuture("result"));
                    
                    return "success";
                } catch (Exception e) {
                    deadlockDetected.set(true);
                    throw new RuntimeException("Deadlock scenario", e);
                }
            });
        };
        
        pipeline.add("mainTask", blockingTask);
        
        // Start pipeline execution (this acquires readLock)
        CompletableFuture<Object> result = pipeline.run("input");
        
        // Wait for task to start executing
        assertTrue(taskStarted.await(2, TimeUnit.SECONDS), "Task should start executing");
        
        // Now signal the task to attempt adding (this will try writeLock)
        attemptAdd.countDown();
        
        // The result should complete or timeout
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(7), () -> {
            Object finalResult = result.join();
            assertNotNull(finalResult);
        }, "CRITICAL: TaskPipeline deadlock detected when modifying during execution!");
    }

    @Test
    @DisplayName("Validate TaskPipeline lock safety - concurrent access patterns")
    void testConcurrentAccessPatterns() throws Exception {
        TaskPipeline pipeline = new TaskPipeline();
        
        // Add initial tasks
        pipeline.add("task1", (input, context) -> CompletableFuture.completedFuture("result1"));
        pipeline.add("task2", (input, context) -> CompletableFuture.completedFuture("result2"));
        
        AtomicBoolean hasException = new AtomicBoolean(false);
        
        // Thread 1: Continuously run the pipeline
        Thread runnerThread = new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    pipeline.run("input_" + i).join();
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                hasException.set(true);
                e.printStackTrace();
            }
        });
        
        // Thread 2: Continuously add new tasks
        Thread builderThread = new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    String taskName = "dynamicTask_" + i;
                    final int taskId = i; // Make effectively final copy
                    pipeline.add(taskName, (input, context) -> 
                        CompletableFuture.completedFuture("dynamic_" + taskId));
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                hasException.set(true);
                e.printStackTrace();
            }
        });
        
        runnerThread.start();
        builderThread.start();
        
        // Wait for both threads
        runnerThread.join(5000);
        builderThread.join(5000);
        
        // Check if threads are still alive (indicating deadlock)
        if (runnerThread.isAlive() || builderThread.isAlive()) {
            runnerThread.interrupt();
            builderThread.interrupt();
            fail("DEADLOCK DETECTED: Threads did not complete within timeout");
        }
        
        assertFalse(hasException.get(), "No exceptions should occur during concurrent access");
    }
    
    @Test
    @DisplayName("Test ResourceManager interface thread safety")
    void testResourceManagerThreadSafety() {
        // Create a resource manager that uses the default synchronized implementation
        final DefaultResourceManager resourceManager = new DefaultResourceManager();
        
        // Test concurrent access to the default tryReserveResources method
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            // Run multiple threads accessing the resource manager
            Thread[] threads = new Thread[10];
            AtomicBoolean hasDeadlock = new AtomicBoolean(false);
            
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    try {
                        for (int j = 0; j < 100; j++) {
                            var req = new dev.shaaf.jgraphlet.task.resource.ResourceRequirements(100, 0.1, false);
                            resourceManager.tryReserveResources(req);
                            resourceManager.releaseResources(req);
                        }
                    } catch (Exception e) {
                        hasDeadlock.set(true);
                    }
                });
                threads[i].start();
            }
            
            for (Thread thread : threads) {
                thread.join();
            }
            
            assertFalse(hasDeadlock.get(), "ResourceManager should handle concurrent access safely");
        }, "ResourceManager operations should complete without deadlock");
    }

    /**
     * A ResourceManager that uses the default synchronized implementation
     * to test for potential issues
     */
    static class DefaultResourceManager implements TaskPipelineConfig.ResourceManager {
        private volatile long usedMemory = 0;
        private final long totalMemory = 1024 * 1024;

        @Override
        public synchronized boolean canSchedule(dev.shaaf.jgraphlet.task.resource.ResourceRequirements requirements) {
            return usedMemory + requirements.estimatedMemoryBytes <= totalMemory;
        }

        @Override
        public synchronized void reserveResources(dev.shaaf.jgraphlet.task.resource.ResourceRequirements requirements) {
            usedMemory += requirements.estimatedMemoryBytes;
        }

        @Override
        public synchronized void releaseResources(dev.shaaf.jgraphlet.task.resource.ResourceRequirements requirements) {
            usedMemory -= requirements.estimatedMemoryBytes;
        }

        @Override
        public dev.shaaf.jgraphlet.task.resource.ResourceConstraint getCurrentConstraints() {
            return dev.shaaf.jgraphlet.task.resource.ResourceConstraint.none();
        }
        
        // Uses the default synchronized implementation from the interface
        // This could be problematic if there are nested calls
    }
}
