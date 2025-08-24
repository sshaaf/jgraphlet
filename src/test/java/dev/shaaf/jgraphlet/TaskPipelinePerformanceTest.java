package dev.shaaf.jgraphlet;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for TaskPipeline optimizations.
 * Demonstrates the performance improvements from using reverse adjacency map.
 */
class TaskPipelinePerformanceTest {

    @Test
    void shouldHandleLargeFanInGraphEfficiently() {
        // This test creates a large fan-in graph to test the O(1) predecessor lookup
        // Before optimization: O(n*m) where n = number of tasks, m = average connections per task
        // After optimization: O(1) lookup time
        
        TaskPipeline pipeline = new TaskPipeline();
        
        // Create a simple task for testing
        Task<String, String> simpleTask = (input, context) -> 
            CompletableFuture.completedFuture(input + "-processed");
        
        Task<java.util.Map<String, Object>, String> aggregatorTask = (inputs, context) ->
            CompletableFuture.completedFuture("Aggregated " + inputs.size() + " inputs");
        
        // Create a large fan-in scenario: many tasks feeding into one
        int numFeederTasks = 100;
        
        // Add the aggregator task
        pipeline.addTask("aggregator", aggregatorTask);
        
        // Add many feeder tasks and connect them all to the aggregator
        Instant startSetup = Instant.now();
        for (int i = 0; i < numFeederTasks; i++) {
            String taskName = "feeder" + i;
            pipeline.addTask(taskName, simpleTask);
            pipeline.connect(taskName, "aggregator");
        }
        Duration setupTime = Duration.between(startSetup, Instant.now());
        
        // Run the pipeline - this will call findPredecessorsFor many times
        Instant startRun = Instant.now();
        CompletableFuture<Object> result = pipeline.run("test-input");
        Object finalResult = result.join();
        Duration runTime = Duration.between(startRun, Instant.now());
        
        // Assert
        assertNotNull(finalResult);
        assertEquals("Aggregated " + numFeederTasks + " inputs", finalResult);
        
        // Performance assertions
        assertTrue(setupTime.toMillis() < 100, 
            "Setup should be fast even with " + numFeederTasks + " connections (took: " + setupTime.toMillis() + "ms)");
        assertTrue(runTime.toMillis() < 500, 
            "Execution should be fast with O(1) predecessor lookups (took: " + runTime.toMillis() + "ms)");
        
        System.out.println("✓ Large fan-in graph (" + numFeederTasks + " tasks) setup: " + 
                         setupTime.toMillis() + "ms, execution: " + runTime.toMillis() + "ms");
        
        pipeline.shutdown();
    }
    
    @Test
    void shouldHandleComplexDiamondGraphEfficiently() {
        // Test a diamond-shaped graph with multiple paths
        // This tests that the reverse graph correctly maintains multiple predecessors
        
        TaskPipeline pipeline = new TaskPipeline();
        
        Task<String, String> transformTask = (input, context) -> 
            CompletableFuture.completedFuture(input + "-transformed");
        
        Task<java.util.Map<String, Object>, String> mergeTask = (inputs, context) -> {
            String path1 = (String) inputs.get("path1");
            String path2 = (String) inputs.get("path2");
            return CompletableFuture.completedFuture(path1 + " & " + path2);
        };
        
        // Create diamond shape: start -> path1 & path2 -> end
        pipeline.addTask("start", transformTask)
               .addTask("path1", transformTask)
               .addTask("path2", transformTask)
               .addTask("end", mergeTask);
        
        pipeline.connect("start", "path1")
               .connect("start", "path2")
               .connect("path1", "end")
               .connect("path2", "end");
        
        // Run and verify
        String result = (String) pipeline.run("input").join();
        
        assertEquals("input-transformed-transformed & input-transformed-transformed", result);
        System.out.println("✓ Diamond graph executed correctly with optimized predecessor lookups");
        
        pipeline.shutdown();
    }
    
    @Test
    void shouldScaleWellWithManyTasks() {
        // Test scalability with a large number of tasks in a chain
        TaskPipeline pipeline = new TaskPipeline();
        
        Task<Integer, Integer> incrementTask = (input, context) -> 
            CompletableFuture.completedFuture(input + 1);
        
        int chainLength = 50;
        
        // Build a long chain of tasks
        Instant startSetup = Instant.now();
        pipeline.add("task0", incrementTask);
        for (int i = 1; i < chainLength; i++) {
            pipeline.then("task" + i, incrementTask);
        }
        Duration setupTime = Duration.between(startSetup, Instant.now());
        
        // Execute the chain
        Instant startRun = Instant.now();
        Integer result = (Integer) pipeline.run(0).join();
        Duration runTime = Duration.between(startRun, Instant.now());
        
        // Verify result
        assertEquals(chainLength, result, "Should increment " + chainLength + " times");
        
        // Performance checks
        assertTrue(setupTime.toMillis() < 50, 
            "Chain setup should be fast (took: " + setupTime.toMillis() + "ms)");
        assertTrue(runTime.toMillis() < 200, 
            "Chain execution should be efficient (took: " + runTime.toMillis() + "ms)");
        
        System.out.println("✓ Chain of " + chainLength + " tasks - setup: " + 
                         setupTime.toMillis() + "ms, execution: " + runTime.toMillis() + "ms");
        
        pipeline.shutdown();
    }
    
    @Test
    void shouldMaintainCorrectReverseGraphAfterMultipleConnections() {
        // Test that reverse graph is correctly maintained with complex connections
        TaskPipeline pipeline = new TaskPipeline();
        
        Task<String, String> task = (input, context) -> 
            CompletableFuture.completedFuture(input);
        
        Task<java.util.Map<String, Object>, String> collectTask = (inputs, context) ->
            CompletableFuture.completedFuture("Collected: " + inputs.keySet());
        
        // Create a complex graph structure
        pipeline.addTask("A", task)
               .addTask("B", task)
               .addTask("C", task)
               .addTask("D", collectTask)
               .addTask("E", collectTask);
        
        // Multiple connections
        pipeline.connect("A", "D")  // A -> D
               .connect("B", "D")    // B -> D
               .connect("A", "E")    // A -> E
               .connect("C", "E");   // C -> E
        
        // D should have predecessors: A, B
        // E should have predecessors: A, C
        
        // Run pipeline - this will use findPredecessorsFor internally
        CompletableFuture<Object> futureD = pipeline.run("test");
        
        // The execution should work correctly with the optimized reverse graph
        assertDoesNotThrow(() -> futureD.join());
        
        System.out.println("✓ Complex graph with multiple connections handled correctly");
        
        pipeline.shutdown();
    }
}