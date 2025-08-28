package dev.shaaf.jgraphlet.pipeline;

import dev.shaaf.jgraphlet.task.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Critical test to validate that the production code doesn't have deadlock issues
 * that real users could encounter.
 */
class DeadlockValidationTest {

    @Test
    @Timeout(10) // Will fail if deadlock occurs
    @DisplayName("CRITICAL: TaskPipeline should not deadlock when modifying during execution")
    void testTaskPipelineReadWriteLockDeadlock() {
        TaskPipeline pipeline = new TaskPipeline();
        AtomicBoolean taskExecuted = new AtomicBoolean(false);
        
        // Add a task that tries to modify the pipeline during execution
        // This simulates a real user scenario where dynamic tasks are added
        Task<String, String> dynamicTask = (input, context) -> {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // This will try to acquire writeLock while readLock is held by run()
                    // This is a classic ReadWriteLock upgrade deadlock!
                    pipeline.add("dynamicTask", (input2, context2) -> 
                        CompletableFuture.completedFuture("dynamic_result"));
                    
                    taskExecuted.set(true);
                    return "processed_" + input;
                } catch (Exception e) {
                    throw new RuntimeException("Deadlock detected in production code!", e);
                }
            });
        };
        
        pipeline.add("mainTask", dynamicTask);
        
        // This should complete without deadlock, but currently it will hang
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            String result = (String) pipeline.run("test").join();
            assertNotNull(result);
            assertTrue(taskExecuted.get(), "Task should have executed successfully");
        }, "DEADLOCK DETECTED: TaskPipeline hangs when modifying during execution!");
    }

    @Test
    @Timeout(10)
    @DisplayName("ResourceManager synchronized block should not cause nested deadlocks")
    void testResourceManagerNestedSynchronization() {
        // Test a resource manager that could cause deadlocks with nested synchronization
        ProblematicResourceManager resourceManager = new ProblematicResourceManager();
        
        TaskPipelineConfig config = TaskPipelineConfig.builder()
            .withResourceManager(resourceManager)
            .build();

        try (EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline(config)) {
            // This should not deadlock even with problematic resource manager
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
                // Create multiple concurrent operations
                for (int i = 0; i < 10; i++) {
                    CompletableFuture.runAsync(() -> {
                        resourceManager.performComplexOperation();
                    });
                }
                Thread.sleep(100); // Give time for operations to run
            }, "ResourceManager should not deadlock with nested synchronization");
        }
    }

    @Test
    @DisplayName("Users should be able to build pipelines concurrently safely")
    void testConcurrentPipelineBuilding() throws InterruptedException {
        // Test that multiple threads can safely build the same pipeline
        TaskPipeline pipeline = new TaskPipeline();
        AtomicBoolean hasDeadlock = new AtomicBoolean(false);
        
        Thread[] builders = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int threadId = i;
            builders[i] = new Thread(() -> {
                try {
                    // Each thread tries to add tasks concurrently
                    for (int j = 0; j < 10; j++) {
                        String taskName = "task_" + threadId + "_" + j;
                        pipeline.add(taskName, (input, context) -> 
                            CompletableFuture.completedFuture("result_" + taskName));
                    }
                } catch (Exception e) {
                    hasDeadlock.set(true);
                }
            });
            builders[i].start();
        }
        
        // Wait for all threads to complete
        for (Thread builder : builders) {
            builder.join(5000); // 5 second timeout
            if (builder.isAlive()) {
                hasDeadlock.set(true);
                builder.interrupt();
            }
        }
        
        assertFalse(hasDeadlock.get(), "Concurrent pipeline building should not cause deadlocks");
    }

    /**
     * Simulates a problematic ResourceManager that users might implement
     * which could cause deadlocks with nested synchronization
     */
    static class ProblematicResourceManager implements TaskPipelineConfig.ResourceManager {
        private final Object lock1 = new Object();
        private final Object lock2 = new Object();
        private volatile boolean busy = false;

        @Override
        public boolean canSchedule(dev.shaaf.jgraphlet.task.resource.ResourceRequirements requirements) {
            synchronized (lock1) {
                return !busy;
            }
        }

        @Override
        public void reserveResources(dev.shaaf.jgraphlet.task.resource.ResourceRequirements requirements) {
            synchronized (lock1) {
                busy = true;
            }
        }

        @Override
        public void releaseResources(dev.shaaf.jgraphlet.task.resource.ResourceRequirements requirements) {
            synchronized (lock1) {
                busy = false;
            }
        }

        @Override
        public dev.shaaf.jgraphlet.task.resource.ResourceConstraint getCurrentConstraints() {
            return dev.shaaf.jgraphlet.task.resource.ResourceConstraint.none();
        }
        
        // This method demonstrates nested synchronization that could cause issues
        public void performComplexOperation() {
            synchronized (lock1) {
                synchronized (lock2) {
                    // Simulate complex operation that could interact with tryReserveResources()
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }
}
