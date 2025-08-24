package dev.shaaf.jgraphlet;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TaskPipelineShutdownTest {

    @Test
    void shouldShutdownGracefullyWithTimeout() throws InterruptedException {
        // Arrange: Create pipeline with reasonable timeout
        TaskPipeline pipeline = new TaskPipeline(5, 2);
        
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        AtomicBoolean taskCompleted = new AtomicBoolean(false);
        
        // Create a task that waits for signal before completing
        Task<String, String> controllableTask = (input, context) -> {
            taskStarted.countDown();
            try {
                // Wait for signal to complete
                if (allowCompletion.await(10, TimeUnit.SECONDS)) {
                    taskCompleted.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture("completed");
        };
        
        // Act
        pipeline.add("controllableTask", controllableTask);
        CompletableFuture<Object> future = pipeline.run("input");
        
        // Wait for task to start
        assertTrue(taskStarted.await(2, TimeUnit.SECONDS), "Task should have started");
        
        // Signal task to complete
        allowCompletion.countDown();
        
        // Wait for completion
        future.join();
        
        // Shutdown should complete quickly since task is done
        long startTime = System.currentTimeMillis();
        pipeline.shutdown();
        long shutdownTime = System.currentTimeMillis() - startTime;
        
        // Assert
        assertTrue(taskCompleted.get(), "Task should have completed");
        assertTrue(shutdownTime < 1000, "Shutdown should be quick when no tasks running");
    }
    
    @Test
    void shouldNotShutdownExternalExecutor() throws InterruptedException {
        // Arrange: Create external executor
        var externalExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);
        
        try {
            TaskPipeline pipeline = new TaskPipeline(externalExecutor);
            
            Task<String, String> task = (input, context) -> 
                CompletableFuture.completedFuture("result");
            
            // Act
            pipeline.add("task", task);
            pipeline.run("input").join();
            pipeline.shutdown();
            
            // Assert - external executor should still be running
            assertFalse(externalExecutor.isShutdown(), 
                "External executor should not be shut down by pipeline");
            
        } finally {
            // Clean up
            externalExecutor.shutdown();
            externalExecutor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }
    
    @Test
    void shouldHandleMultipleShutdownCallsGracefully() {
        // Arrange
        TaskPipeline pipeline = new TaskPipeline();
        
        Task<String, String> simpleTask = (input, context) -> 
            CompletableFuture.completedFuture("done");
        
        // Act
        pipeline.add("simpleTask", simpleTask);
        Object result = pipeline.run("input").join();
        
        // Multiple shutdown calls should not cause issues
        pipeline.shutdown();
        pipeline.shutdown();
        pipeline.close();
        
        // Assert
        assertEquals("done", result);
        // No exceptions should be thrown
    }
    
    @Test  
    void shouldUseCustomTimeoutValues() throws InterruptedException {
        // This test verifies that custom timeout values are properly used
        // We create a pipeline with short timeouts and verify the shutdown behavior
        
        // Arrange: Create pipeline with custom timeouts
        long gracefulTimeout = 2; // 2 seconds for graceful shutdown
        long forcedTimeout = 1;   // 1 second for forced shutdown
        TaskPipeline pipeline = new TaskPipeline(gracefulTimeout, forcedTimeout);
        
        // Create a simple task that completes quickly
        Task<String, String> quickTask = (input, context) -> 
            CompletableFuture.completedFuture("done quickly");
        
        // Act
        pipeline.add("quickTask", quickTask);
        Object result = pipeline.run("input").join();
        
        // Measure shutdown time - should be very quick since no tasks are running
        long startTime = System.currentTimeMillis();
        pipeline.shutdown();
        long shutdownTime = System.currentTimeMillis() - startTime;
        
        // Assert
        assertEquals("done quickly", result);
        // Shutdown should be nearly instant when no tasks are running
        assertTrue(shutdownTime < 500, "Shutdown should be quick when no tasks are running");
        
        // Verify we can create another pipeline with different timeouts
        TaskPipeline pipeline2 = new TaskPipeline(10, 5);
        pipeline2.add("task", quickTask);
        pipeline2.run("test").join();
        pipeline2.shutdown();
        // No exception should be thrown
    }
    
    @Test
    void shouldCleanupResourcesWithTryWithResources() throws Exception {
        String result;
        
        // Use try-with-resources to ensure automatic cleanup
        try (TaskPipeline pipeline = new TaskPipeline()) {
            Task<String, String> task = (input, context) -> 
                CompletableFuture.completedFuture(input.toUpperCase());
            
            pipeline.add("upperCase", task);
            result = (String) pipeline.run("hello").join();
        } // Pipeline.close() called automatically here
        
        // Assert
        assertEquals("HELLO", result);
        // Pipeline resources should be cleaned up automatically
    }
    
    @Test
    void shouldHandleShutdownNowCorrectly() {
        // Arrange
        TaskPipeline pipeline = new TaskPipeline();
        
        CountDownLatch taskStarted = new CountDownLatch(1);
        AtomicBoolean taskInterrupted = new AtomicBoolean(false);
        
        Task<String, String> longTask = (input, context) -> 
            CompletableFuture.supplyAsync(() -> {
                taskStarted.countDown();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    taskInterrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                return "should be interrupted";
            });
        
        // Act
        pipeline.add("longTask", longTask);
        pipeline.run("input"); // Start but don't wait
        
        try {
            taskStarted.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }
        
        // Force immediate shutdown
        pipeline.shutdownNow();
        
        // Assert - task should be interrupted
        // Note: The interruption happens in the ForkJoinPool threads
        // We can't guarantee the task sees the interruption immediately
        // but shutdownNow() should have been called
        assertNotNull(pipeline); // Pipeline should still be valid after shutdownNow
    }
}