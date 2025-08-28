package dev.shaaf.jgraphlet.task;

import dev.shaaf.jgraphlet.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for built-in task types (MapTask, FilterTask, ReduceTask).
 */
class BuiltinTaskTypesTest {

    // ========================================================================
    // MapTask Tests
    // ========================================================================

    @Test
    @DisplayName("Map task should transform all elements")
    void testMapTaskBasicTransformation() throws Exception {
        TestSquareMapTask mapTask = new TestSquareMapTask();
        PipelineContext context = new PipelineContext();

        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5);
        CompletableFuture<List<Integer>> future = mapTask.execute(input, context);
        List<Integer> result = future.join();

        assertNotNull(result);
        assertEquals(5, result.size());
        assertEquals(Arrays.asList(1, 4, 9, 16, 25), result);
    }

    @Test
    @DisplayName("Map task should handle empty input")
    void testMapTaskEmptyInput() throws Exception {
        TestSquareMapTask mapTask = new TestSquareMapTask();
        PipelineContext context = new PipelineContext();

        List<Integer> emptyInput = Collections.emptyList();
        CompletableFuture<List<Integer>> future = mapTask.execute(emptyInput, context);
        List<Integer> result = future.join();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Map task should preserve order")
    void testMapTaskOrderPreservation() throws Exception {
        TestStringLengthMapTask mapTask = new TestStringLengthMapTask();
        PipelineContext context = new PipelineContext();

        List<String> input = Arrays.asList("a", "bb", "ccc", "dddd", "eeeee");
        CompletableFuture<List<Integer>> future = mapTask.execute(input, context);
        List<Integer> result = future.join();

        assertEquals(Arrays.asList(1, 2, 3, 4, 5), result);
    }

    @Test
    @DisplayName("Map task should support parallel execution when enabled")
    void testMapTaskParallelExecution() throws Exception {
        TestParallelMapTask parallelMapTask = new TestParallelMapTask();
        PipelineContext context = new PipelineContext();

        assertTrue(parallelMapTask.supportsParallelExecution());

        List<Integer> largeInput = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            largeInput.add(i);
        }

        CompletableFuture<List<Integer>> future = parallelMapTask.execute(largeInput, context);
        List<Integer> result = future.join();

        assertEquals(1000, result.size());
        // Verify transformation was applied
        assertEquals(2, result.get(0)); // 1 * 2 = 2
        assertEquals(2000, result.get(999)); // 1000 * 2 = 2000
    }

    @Test
    @DisplayName("Map task should handle null elements appropriately")
    void testMapTaskNullHandling() throws Exception {
        TestNullSafeMapTask nullSafeMap = new TestNullSafeMapTask();
        PipelineContext context = new PipelineContext();

        List<String> inputWithNulls = Arrays.asList("hello", null, "world", null, "test");
        CompletableFuture<List<Integer>> future = nullSafeMap.execute(inputWithNulls, context);
        List<Integer> result = future.join();

        assertEquals(Arrays.asList(5, 0, 5, 0, 4), result);
    }

    // ========================================================================
    // FilterTask Tests
    // ========================================================================

    @Test
    @DisplayName("Filter task should select elements based on predicate")
    void testFilterTaskBasicFiltering() throws Exception {
        TestEvenFilterTask filterTask = new TestEvenFilterTask();
        PipelineContext context = new PipelineContext();

        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        CompletableFuture<List<Integer>> future = filterTask.execute(input, context);
        List<Integer> result = future.join();

        assertEquals(Arrays.asList(2, 4, 6, 8, 10), result);
    }

    @Test
    @DisplayName("Filter task should handle empty input")
    void testFilterTaskEmptyInput() throws Exception {
        TestEvenFilterTask filterTask = new TestEvenFilterTask();
        PipelineContext context = new PipelineContext();

        List<Integer> emptyInput = Collections.emptyList();
        CompletableFuture<List<Integer>> future = filterTask.execute(emptyInput, context);
        List<Integer> result = future.join();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Filter task should preserve order of selected elements")
    void testFilterTaskOrderPreservation() throws Exception {
        TestPositiveFilterTask filterTask = new TestPositiveFilterTask();
        PipelineContext context = new PipelineContext();

        List<Integer> input = Arrays.asList(-3, 1, -2, 4, -1, 7, -5, 9);
        CompletableFuture<List<Integer>> future = filterTask.execute(input, context);
        List<Integer> result = future.join();

        assertEquals(Arrays.asList(1, 4, 7, 9), result);
    }

    @Test
    @DisplayName("Filter task should handle all elements being filtered out")
    void testFilterTaskAllFiltered() throws Exception {
        TestPositiveFilterTask filterTask = new TestPositiveFilterTask();
        PipelineContext context = new PipelineContext();

        List<Integer> allNegative = Arrays.asList(-1, -2, -3, -4, -5);
        CompletableFuture<List<Integer>> future = filterTask.execute(allNegative, context);
        List<Integer> result = future.join();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Filter task should handle no elements being filtered out")
    void testFilterTaskNoneFiltered() throws Exception {
        TestPositiveFilterTask filterTask = new TestPositiveFilterTask();
        PipelineContext context = new PipelineContext();

        List<Integer> allPositive = Arrays.asList(1, 2, 3, 4, 5);
        CompletableFuture<List<Integer>> future = filterTask.execute(allPositive, context);
        List<Integer> result = future.join();

        assertEquals(allPositive, result);
    }

    @Test
    @DisplayName("Filter task should support parallel execution")
    void testFilterTaskParallelExecution() throws Exception {
        TestParallelFilterTask parallelFilter = new TestParallelFilterTask();
        PipelineContext context = new PipelineContext();

        assertTrue(parallelFilter.supportsParallelExecution());

        List<Integer> largeInput = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            largeInput.add(i);
        }

        CompletableFuture<List<Integer>> future = parallelFilter.execute(largeInput, context);
        List<Integer> result = future.join();

        // Should contain only multiples of 10
        assertEquals(100, result.size());
        assertEquals(10, result.get(0));
        assertEquals(1000, result.get(99));
    }

    // ========================================================================
    // ReduceTask Tests
    // ========================================================================

    @Test
    @DisplayName("Reduce task should aggregate elements correctly")
    void testReduceTaskBasicAggregation() throws Exception {
        TestSumReduceTask reduceTask = new TestSumReduceTask();
        PipelineContext context = new PipelineContext();

        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5);
        CompletableFuture<Integer> future = reduceTask.execute(input, context);
        Integer result = future.join();

        assertEquals(15, result);
    }

    @Test
    @DisplayName("Reduce task should handle empty input with identity")
    void testReduceTaskEmptyInput() throws Exception {
        TestSumReduceTask reduceTask = new TestSumReduceTask();
        PipelineContext context = new PipelineContext();

        List<Integer> emptyInput = Collections.emptyList();
        CompletableFuture<Integer> future = reduceTask.execute(emptyInput, context);
        Integer result = future.join();

        assertEquals(0, result); // Identity value for sum
    }

    @Test
    @DisplayName("Reduce task should handle single element")
    void testReduceTaskSingleElement() throws Exception {
        TestSumReduceTask reduceTask = new TestSumReduceTask();
        PipelineContext context = new PipelineContext();

        List<Integer> singleElement = Arrays.asList(42);
        CompletableFuture<Integer> future = reduceTask.execute(singleElement, context);
        Integer result = future.join();

        assertEquals(42, result);
    }

    @Test
    @DisplayName("Reduce task should work with different aggregation operations")
    void testReduceTaskDifferentOperations() throws Exception {
        // Test multiplication reduce
        TestProductReduceTask productTask = new TestProductReduceTask();
        PipelineContext context = new PipelineContext();

        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5);
        CompletableFuture<Integer> future = productTask.execute(input, context);
        Integer result = future.join();

        assertEquals(120, result); // 1*2*3*4*5 = 120

        // Test string concatenation reduce
        TestStringConcatenationReduceTask stringTask = new TestStringConcatenationReduceTask();
        List<String> stringInput = Arrays.asList("Hello", " ", "World", "!");
        CompletableFuture<String> stringFuture = stringTask.execute(stringInput, context);
        String stringResult = stringFuture.join();

        assertEquals("Hello World!", stringResult);
    }

    @Test
    @DisplayName("Reduce task should support parallel execution")
    void testReduceTaskParallelExecution() throws Exception {
        TestParallelSumReduceTask parallelReduce = new TestParallelSumReduceTask();
        PipelineContext context = new PipelineContext();

        assertTrue(parallelReduce.supportsParallelExecution());

        List<Integer> largeInput = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            largeInput.add(i);
        }

        CompletableFuture<Integer> future = parallelReduce.execute(largeInput, context);
        Integer result = future.join();

        assertEquals(500500, result); // Sum of 1 to 1000
    }

    // ========================================================================
    // Combined Task Tests
    // ========================================================================

    @Test
    @DisplayName("Map-Filter-Reduce chain should work correctly")
    void testMapFilterReduceChain() throws Exception {
        PipelineContext context = new PipelineContext();

        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        // Map: square each number
        TestSquareMapTask mapTask = new TestSquareMapTask();
        CompletableFuture<List<Integer>> mapFuture = mapTask.execute(input, context);
        List<Integer> mapped = mapFuture.join();

        // Filter: keep only even numbers
        TestEvenFilterTask filterTask = new TestEvenFilterTask();
        CompletableFuture<List<Integer>> filterFuture = filterTask.execute(mapped, context);
        List<Integer> filtered = filterFuture.join();

        // Reduce: sum all remaining numbers
        TestSumReduceTask reduceTask = new TestSumReduceTask();
        CompletableFuture<Integer> reduceFuture = reduceTask.execute(filtered, context);
        Integer result = reduceFuture.join();

        // Squares: [1, 4, 9, 16, 25, 36, 49, 64, 81, 100]
        // Even squares: [4, 16, 36, 64, 100]
        // Sum: 220
        assertEquals(220, result);
    }

    // ========================================================================
    // Test Implementation Classes
    // ========================================================================

    // MapTask implementations
    static class TestSquareMapTask extends MapTask<Integer, Integer> {
        @Override
        protected Integer map(Integer input) {
            return input * input;
        }

        @Override
        protected boolean supportsParallelExecution() {
            return true;
        }
    }

    static class TestStringLengthMapTask extends MapTask<String, Integer> {
        @Override
        protected Integer map(String input) {
            return input != null ? input.length() : 0;
        }

        @Override
        protected boolean supportsParallelExecution() {
            return true;
        }
    }

    static class TestParallelMapTask extends MapTask<Integer, Integer> {
        @Override
        protected Integer map(Integer input) {
            return input * 2;
        }

        @Override
        protected boolean supportsParallelExecution() {
            return true;
        }
    }

    static class TestNullSafeMapTask extends MapTask<String, Integer> {
        @Override
        protected Integer map(String input) {
            return input != null ? input.length() : 0;
        }

        @Override
        protected boolean supportsParallelExecution() {
            return true;
        }
    }

    // FilterTask implementations
    static class TestEvenFilterTask extends FilterTask<Integer> {
        @Override
        protected boolean test(Integer element) {
            return element % 2 == 0;
        }

        @Override
        protected boolean supportsParallelExecution() {
            return true;
        }
    }

    static class TestPositiveFilterTask extends FilterTask<Integer> {
        @Override
        protected boolean test(Integer element) {
            return element > 0;
        }

        @Override
        protected boolean supportsParallelExecution() {
            return true;
        }
    }

    static class TestParallelFilterTask extends FilterTask<Integer> {
        @Override
        protected boolean test(Integer element) {
            return element % 10 == 0; // Multiples of 10
        }

        @Override
        protected boolean supportsParallelExecution() {
            return true;
        }
    }

    // ReduceTask implementations
    static class TestSumReduceTask extends ReduceTask<Integer, Integer> {
        @Override
        protected Integer reduce(Integer accumulator, Integer next) {
            return accumulator + next;
        }

        @Override
        protected Integer identity() {
            return 0;
        }

        @Override
        protected boolean supportsParallelExecution() {
            return true;
        }
    }

    static class TestProductReduceTask extends ReduceTask<Integer, Integer> {
        @Override
        protected Integer reduce(Integer accumulator, Integer next) {
            return accumulator * next;
        }

        @Override
        protected Integer identity() {
            return 1;
        }

        @Override
        protected boolean supportsParallelExecution() {
            return true;
        }
    }

    static class TestStringConcatenationReduceTask extends ReduceTask<String, String> {
        @Override
        protected String reduce(String accumulator, String next) {
            return accumulator + next;
        }

        @Override
        protected String identity() {
            return "";
        }

        @Override
        protected boolean supportsParallelExecution() {
            return false; // Order matters for string concatenation
        }
    }

    static class TestParallelSumReduceTask extends ReduceTask<Integer, Integer> {
        @Override
        protected Integer reduce(Integer accumulator, Integer next) {
            return accumulator + next;
        }

        @Override
        protected Integer identity() {
            return 0;
        }

        @Override
        protected boolean supportsParallelExecution() {
            return true;
        }
    }
}
