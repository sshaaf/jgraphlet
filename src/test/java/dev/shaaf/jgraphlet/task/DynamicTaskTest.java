package dev.shaaf.jgraphlet.task;

import dev.shaaf.jgraphlet.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for DynamicTask interface and implementations.
 */
class DynamicTaskTest {

    @Test
    @DisplayName("Dynamic task should create appropriate number of children based on input")
    void testDynamicTaskChildCreation() {
        TestDynamicTask dynamicTask = new TestDynamicTask();
        PipelineContext context = new PipelineContext();
        
        // Test with different input sizes
        List<String> smallInput = Arrays.asList("a", "b");
        List<Task<?, ?>> smallChildren = dynamicTask.createChildren(smallInput, context);
        assertEquals(2, smallChildren.size());
        
        List<String> largeInput = Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h");
        List<Task<?, ?>> largeChildren = dynamicTask.createChildren(largeInput, context);
        assertEquals(4, largeChildren.size()); // Max children = 4
    }

    @Test
    @DisplayName("Dynamic task should combine results from children correctly")
    void testDynamicTaskResultCombination() {
        TestDynamicTask dynamicTask = new TestDynamicTask();
        PipelineContext context = new PipelineContext();
        
        // Simulate child results
        List<Object> childResults = Arrays.asList(
            Arrays.asList("result1", "result2"),
            Arrays.asList("result3", "result4"),
            Arrays.asList("result5")
        );
        
        List<String> combined = dynamicTask.combineResults(childResults, context);
        
        assertNotNull(combined);
        assertEquals(5, combined.size());
        assertEquals(Arrays.asList("result1", "result2", "result3", "result4", "result5"), combined);
    }

    @Test
    @DisplayName("Dynamic task should respect max children limit")
    void testDynamicTaskMaxChildrenLimit() {
        TestDynamicTask dynamicTask = new TestDynamicTask();
        PipelineContext context = new PipelineContext();
        
        // Create input larger than max children
        List<String> largeInput = Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        List<Task<?, ?>> children = dynamicTask.createChildren(largeInput, context);
        
        assertTrue(children.size() <= dynamicTask.getMaxChildren());
        assertEquals(4, children.size()); // Should be limited to max children
    }

    @Test
    @DisplayName("Dynamic task should indicate concurrent execution capability")
    void testDynamicTaskConcurrencySettings() {
        TestDynamicTask dynamicTask = new TestDynamicTask();
        
        assertTrue(dynamicTask.allowConcurrentChildren());
        assertEquals(4, dynamicTask.getMaxChildren());
    }

    @Test
    @DisplayName("Dynamic task with sequential execution should work correctly")
    void testSequentialDynamicTask() {
        TestSequentialDynamicTask sequentialTask = new TestSequentialDynamicTask();
        PipelineContext context = new PipelineContext();
        
        assertFalse(sequentialTask.allowConcurrentChildren());
        
        List<String> input = Arrays.asList("a", "b", "c");
        List<Task<?, ?>> children = sequentialTask.createChildren(input, context);
        assertEquals(3, children.size());
        
        // Test combination
        List<Object> childResults = Arrays.asList("1", "2", "3");
        String combined = sequentialTask.combineResults(childResults, context);
        assertEquals("1-2-3", combined);
    }

    @Test
    @DisplayName("Dynamic task child execution should work correctly")
    void testDynamicTaskChildExecution() throws Exception {
        TestDynamicTask dynamicTask = new TestDynamicTask();
        PipelineContext context = new PipelineContext();
        
        List<String> input = Arrays.asList("test1", "test2");
        List<Task<?, ?>> children = dynamicTask.createChildren(input, context);
        
        // Execute children
        List<CompletableFuture<Object>> futures = new ArrayList<>();
        for (Task<?, ?> child : children) {
            @SuppressWarnings("unchecked")
            Task<Object, Object> typedChild = (Task<Object, Object>) child;
            futures.add(typedChild.execute(input, context));
        }
        
        // Wait for completion and collect results
        List<Object> results = new ArrayList<>();
        for (CompletableFuture<Object> future : futures) {
            results.add(future.join());
        }
        
        // Combine results
        List<String> finalResult = dynamicTask.combineResults(results, context);
        
        assertNotNull(finalResult);
        assertFalse(finalResult.isEmpty());
    }

    // ========================================================================
    // Test Implementation Classes
    // ========================================================================

    /**
     * Test implementation of DynamicTask that creates children based on input size
     */
    static class TestDynamicTask implements DynamicTask<List<String>, List<String>> {

        @Override
        public CompletableFuture<List<String>> execute(List<String> input, PipelineContext context) {
            // This method should not be called for dynamic tasks in normal usage
            throw new UnsupportedOperationException("Dynamic tasks use createChildren/combineResults pattern");
        }

        @Override
        public List<Task<?, ?>> createChildren(List<String> input, PipelineContext context) {
            List<Task<?, ?>> children = new ArrayList<>();
            
            // Create one child per input element, up to max children
            int numChildren = Math.min(input.size(), getMaxChildren());
            int itemsPerChild = Math.max(1, input.size() / numChildren);
            
            for (int i = 0; i < numChildren; i++) {
                int startIdx = i * itemsPerChild;
                int endIdx = (i == numChildren - 1) ? input.size() : Math.min((i + 1) * itemsPerChild, input.size());
                
                List<String> childInput = input.subList(startIdx, endIdx);
                children.add(new TestChildTask(childInput));
            }
            
            return children;
        }

        @Override
        public List<String> combineResults(List<Object> childResults, PipelineContext context) {
            List<String> combined = new ArrayList<>();
            
            for (Object result : childResults) {
                if (result instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> listResult = (List<String>) result;
                    combined.addAll(listResult);
                }
            }
            
            return combined;
        }

        @Override
        public int getMaxChildren() {
            return 4;
        }

        @Override
        public boolean allowConcurrentChildren() {
            return true;
        }
    }

    /**
     * Test child task that processes a portion of the input
     */
    static class TestChildTask implements Task<Object, List<String>> {
        private final List<String> dataToProcess;

        TestChildTask(List<String> dataToProcess) {
            this.dataToProcess = dataToProcess;
        }

        @Override
        public CompletableFuture<List<String>> execute(Object input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                List<String> result = new ArrayList<>();
                for (String item : dataToProcess) {
                    result.add("processed_" + item);
                }
                return result;
            });
        }
    }

    /**
     * Test dynamic task that requires sequential execution
     */
    static class TestSequentialDynamicTask implements DynamicTask<List<String>, String> {

        @Override
        public CompletableFuture<String> execute(List<String> input, PipelineContext context) {
            throw new UnsupportedOperationException("Dynamic tasks use createChildren/combineResults pattern");
        }

        @Override
        public List<Task<?, ?>> createChildren(List<String> input, PipelineContext context) {
            List<Task<?, ?>> children = new ArrayList<>();
            
            for (String item : input) {
                children.add(new TestSequentialChildTask(item));
            }
            
            return children;
        }

        @Override
        public String combineResults(List<Object> childResults, PipelineContext context) {
            return childResults.stream()
                .map(Object::toString)
                .reduce((a, b) -> a + "-" + b)
                .orElse("");
        }

        @Override
        public int getMaxChildren() {
            return -1; // No limit
        }

        @Override
        public boolean allowConcurrentChildren() {
            return false; // Require sequential execution
        }
    }

    /**
     * Sequential child task
     */
    static class TestSequentialChildTask implements Task<Object, String> {
        private final String data;

        TestSequentialChildTask(String data) {
            this.data = data;
        }

        @Override
        public CompletableFuture<String> execute(Object input, PipelineContext context) {
            return CompletableFuture.completedFuture(data.toUpperCase());
        }
    }
}
