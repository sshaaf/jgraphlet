package dev.shaaf.jgraphlet.task;

import dev.shaaf.jgraphlet.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for SplittableTask interface and implementations.
 */
class SplittableTaskTest {

    // ========================================================================
    // Basic SplittableTask Tests
    // ========================================================================

    @Test
    @DisplayName("Splittable task should determine if input can be split")
    void testSplittableTaskCanSplit() {
        TestSumSplittableTask splittableTask = new TestSumSplittableTask();

        // Small input - shouldn't split
        List<Integer> smallInput = Arrays.asList(1, 2);
        assertFalse(splittableTask.canSplit(smallInput));

        // Large input - should split
        List<Integer> largeInput = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertTrue(splittableTask.canSplit(largeInput));
    }

    @Test
    @DisplayName("Splittable task should estimate work size correctly")
    void testSplittableTaskWorkSizeEstimation() {
        TestSumSplittableTask splittableTask = new TestSumSplittableTask();

        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5);
        assertEquals(5, splittableTask.estimateWorkSize(input));

        List<Integer> emptyInput = Collections.emptyList();
        assertEquals(0, splittableTask.estimateWorkSize(emptyInput));
    }

    @Test
    @DisplayName("Splittable task should respect minimum split size")
    void testSplittableTaskMinimumSplitSize() {
        TestSumSplittableTask splittableTask = new TestSumSplittableTask();

        assertEquals(3, splittableTask.getMinimumSplitSize());

        // Input smaller than minimum split size
        List<Integer> tooSmall = Arrays.asList(1, 2);
        assertFalse(splittableTask.canSplit(tooSmall));
    }

    @Test
    @DisplayName("Splittable task should respect maximum split parts")
    void testSplittableTaskMaximumSplitParts() {
        TestSumSplittableTask splittableTask = new TestSumSplittableTask();

        assertEquals(4, splittableTask.getMaximumSplitParts());

        List<Integer> largeInput = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            largeInput.add(i);
        }

        List<SplittableTask<List<Integer>, Integer>> splitTasks = splittableTask.split(largeInput, 10);
        assertTrue(splitTasks.size() <= splittableTask.getMaximumSplitParts());
    }

    @Test
    @DisplayName("Splittable task should split work appropriately")
    void testSplittableTaskSplitting() {
        TestSumSplittableTask splittableTask = new TestSumSplittableTask();

        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        List<SplittableTask<List<Integer>, Integer>> splitTasks = splittableTask.split(input, 3);

        assertNotNull(splitTasks);
        assertEquals(3, splitTasks.size());

        // Verify each split task is properly configured
        for (SplittableTask<List<Integer>, Integer> task : splitTasks) {
            assertNotNull(task);
            assertTrue(task instanceof TestSumSplittablePartTask);
        }
    }

    @Test
    @DisplayName("Splittable task should execute and combine results correctly")
    void testSplittableTaskExecuteAndCombine() throws Exception {
        TestSumSplittableTask splittableTask = new TestSumSplittableTask();
        PipelineContext context = new PipelineContext();

        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        // Split the task
        List<SplittableTask<List<Integer>, Integer>> splitTasks = splittableTask.split(input, 3);

        // Execute each split
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (SplittableTask<List<Integer>, Integer> task : splitTasks) {
            futures.add(task.execute(input, context));
        }

        // Collect results
        List<Integer> splitResults = new ArrayList<>();
        for (CompletableFuture<Integer> future : futures) {
            splitResults.add(future.join());
        }

        // Combine results
        Integer finalResult = splittableTask.combineResults(splitResults, context);

        // Should equal the sum of 1 to 10 = 55
        assertEquals(55, finalResult);
    }

    @Test
    @DisplayName("Splittable task should handle unsplittable execution")
    void testSplittableTaskUnsplittableExecution() throws Exception {
        TestSumSplittableTask splittableTask = new TestSumSplittableTask();
        PipelineContext context = new PipelineContext();

        // Small input that can't be split
        List<Integer> smallInput = Arrays.asList(1, 2);
        
        CompletableFuture<Integer> future = splittableTask.execute(smallInput, context);
        Integer result = future.join();

        assertEquals(3, result); // Sum of 1 + 2
    }

    // ========================================================================
    // Advanced SplittableTask Tests
    // ========================================================================

    @Test
    @DisplayName("Splittable task should handle different data types")
    void testSplittableTaskDifferentDataTypes() throws Exception {
        TestStringConcatenationSplittableTask stringTask = new TestStringConcatenationSplittableTask();
        PipelineContext context = new PipelineContext();

        List<String> input = Arrays.asList("Hello", " ", "World", " ", "from", " ", "JGraphlet", "!");

        // Test splitting
        assertTrue(stringTask.canSplit(input));
        List<SplittableTask<List<String>, String>> splitTasks = stringTask.split(input, 2);
        assertEquals(2, splitTasks.size());

        // Execute splits
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (SplittableTask<List<String>, String> task : splitTasks) {
            futures.add(task.execute(input, context));
        }

        List<String> splitResults = new ArrayList<>();
        for (CompletableFuture<String> future : futures) {
            splitResults.add(future.join());
        }

        // Combine results
        String finalResult = stringTask.combineResults(splitResults, context);
        assertEquals("Hello World from JGraphlet!", finalResult);
    }

    @Test
    @DisplayName("Splittable task should handle load balancing")
    void testSplittableTaskLoadBalancing() {
        TestLoadBalancingSplittableTask loadBalancingTask = new TestLoadBalancingSplittableTask();

        List<Integer> unevenInput = Arrays.asList(1, 100, 2, 200, 3, 300, 4, 400);

        // Test that load balancing affects splitting
        List<SplittableTask<List<Integer>, Integer>> splitTasks = loadBalancingTask.split(unevenInput, 2);
        assertEquals(2, splitTasks.size());

        // Verify load balancing was considered
        assertTrue(loadBalancingTask.wasLoadBalancingConsidered());
    }

    @Test
    @DisplayName("Splittable task should handle work stealing scenarios")
    void testSplittableTaskWorkStealing() throws Exception {
        TestWorkStealingSplittableTask workStealingTask = new TestWorkStealingSplittableTask();
        PipelineContext context = new PipelineContext();

        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

        // Split into uneven parts to simulate work stealing need
        List<SplittableTask<List<Integer>, Integer>> splitTasks = workStealingTask.split(input, 3);

        // Execute tasks (some will finish faster, simulating work stealing opportunity)
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (SplittableTask<List<Integer>, Integer> task : splitTasks) {
            futures.add(task.execute(input, context));
        }

        List<Integer> results = new ArrayList<>();
        for (CompletableFuture<Integer> future : futures) {
            results.add(future.join());
        }

        Integer finalResult = workStealingTask.combineResults(results, context);
        assertEquals(78, finalResult); // Sum of 1 to 12
    }

    @Test
    @DisplayName("Splittable task should handle edge cases")
    void testSplittableTaskEdgeCases() throws Exception {
        TestSumSplittableTask splittableTask = new TestSumSplittableTask();
        PipelineContext context = new PipelineContext();

        // Empty input
        List<Integer> emptyInput = Collections.emptyList();
        assertFalse(splittableTask.canSplit(emptyInput));
        
        CompletableFuture<Integer> emptyFuture = splittableTask.execute(emptyInput, context);
        Integer emptyResult = emptyFuture.join();
        assertEquals(0, emptyResult);

        // Single element
        List<Integer> singleElement = Arrays.asList(42);
        assertFalse(splittableTask.canSplit(singleElement));
        
        CompletableFuture<Integer> singleFuture = splittableTask.execute(singleElement, context);
        Integer singleResult = singleFuture.join();
        assertEquals(42, singleResult);
    }

    @Test
    @DisplayName("Splittable task should provide accurate split metrics")
    void testSplittableTaskSplitMetrics() {
        TestMetricsSplittableTask metricsTask = new TestMetricsSplittableTask();

        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        // Get split efficiency
        double efficiency = metricsTask.estimateSplitEfficiency(input, 3);
        assertTrue(efficiency > 0.0 && efficiency <= 1.0);

        // Get optimal split count
        int optimalSplits = metricsTask.recommendSplitCount(input);
        assertTrue(optimalSplits > 0);
        assertTrue(optimalSplits <= metricsTask.getMaximumSplitParts());
    }

    // ========================================================================
    // Test Implementation Classes
    // ========================================================================

    /**
     * Test splittable task that sums integers
     */
    static class TestSumSplittableTask implements SplittableTask<List<Integer>, Integer> {

        @Override
        public CompletableFuture<Integer> execute(List<Integer> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                return input.stream().mapToInt(Integer::intValue).sum();
            });
        }

        @Override
        public boolean canSplit(List<Integer> input) {
            return input.size() >= getMinimumSplitSize();
        }

        @Override
        public List<SplittableTask<List<Integer>, Integer>> split(List<Integer> input, int targetParts) {
            List<SplittableTask<List<Integer>, Integer>> tasks = new ArrayList<>();
            int actualParts = Math.min(targetParts, getMaximumSplitParts());
            int chunkSize = Math.max(1, input.size() / actualParts);

            for (int i = 0; i < actualParts; i++) {
                int startIdx = i * chunkSize;
                int endIdx = (i == actualParts - 1) ? input.size() : Math.min((i + 1) * chunkSize, input.size());
                List<Integer> chunk = input.subList(startIdx, endIdx);
                tasks.add(new TestSumSplittablePartTask(chunk));
            }

            return tasks;
        }

        @Override
        public Integer combineResults(List<Integer> splitResults, PipelineContext context) {
            return splitResults.stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public long estimateWorkSize(List<Integer> input) {
            return input.size();
        }

        @Override
        public long getMinimumSplitSize() {
            return 3;
        }

        @Override
        public int getMaximumSplitParts() {
            return 4;
        }
    }

    /**
     * Test splittable part task for sum operations
     */
    static class TestSumSplittablePartTask implements SplittableTask<List<Integer>, Integer> {
        private final List<Integer> chunk;

        TestSumSplittablePartTask(List<Integer> chunk) {
            this.chunk = chunk;
        }

        @Override
        public CompletableFuture<Integer> execute(List<Integer> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                return chunk.stream().mapToInt(Integer::intValue).sum();
            });
        }

        @Override
        public boolean canSplit(List<Integer> input) {
            return false; // Already split
        }

        @Override
        public List<SplittableTask<List<Integer>, Integer>> split(List<Integer> input, int targetParts) {
            throw new UnsupportedOperationException("Already split");
        }

        @Override
        public Integer combineResults(List<Integer> splitResults, PipelineContext context) {
            return splitResults.stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public long estimateWorkSize(List<Integer> input) {
            return chunk.size();
        }
    }

    /**
     * Test splittable task for string concatenation
     */
    static class TestStringConcatenationSplittableTask implements SplittableTask<List<String>, String> {

        @Override
        public CompletableFuture<String> execute(List<String> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                return String.join("", input);
            });
        }

        @Override
        public boolean canSplit(List<String> input) {
            return input.size() >= 4;
        }

        @Override
        public List<SplittableTask<List<String>, String>> split(List<String> input, int targetParts) {
            List<SplittableTask<List<String>, String>> tasks = new ArrayList<>();
            int chunkSize = Math.max(1, input.size() / targetParts);

            for (int i = 0; i < targetParts; i++) {
                int startIdx = i * chunkSize;
                int endIdx = (i == targetParts - 1) ? input.size() : Math.min((i + 1) * chunkSize, input.size());
                List<String> chunk = input.subList(startIdx, endIdx);
                tasks.add(new TestStringConcatenationPartTask(chunk));
            }

            return tasks;
        }

        @Override
        public String combineResults(List<String> splitResults, PipelineContext context) {
            return String.join("", splitResults);
        }

        @Override
        public long estimateWorkSize(List<String> input) {
            return input.stream().mapToInt(String::length).sum();
        }
    }

    /**
     * String concatenation part task
     */
    static class TestStringConcatenationPartTask implements SplittableTask<List<String>, String> {
        private final List<String> chunk;

        TestStringConcatenationPartTask(List<String> chunk) {
            this.chunk = chunk;
        }

        @Override
        public CompletableFuture<String> execute(List<String> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                return String.join("", chunk);
            });
        }

        @Override
        public boolean canSplit(List<String> input) {
            return false;
        }

        @Override
        public List<SplittableTask<List<String>, String>> split(List<String> input, int targetParts) {
            throw new UnsupportedOperationException("Already split");
        }

        @Override
        public String combineResults(List<String> splitResults, PipelineContext context) {
            return String.join("", splitResults);
        }

        @Override
        public long estimateWorkSize(List<String> input) {
            return chunk.stream().mapToInt(String::length).sum();
        }
    }

    /**
     * Test splittable task with load balancing considerations
     */
    static class TestLoadBalancingSplittableTask implements SplittableTask<List<Integer>, Integer> {
        private boolean loadBalancingConsidered = false;

        @Override
        public CompletableFuture<Integer> execute(List<Integer> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> 
                input.stream().mapToInt(Integer::intValue).sum()
            );
        }

        @Override
        public boolean canSplit(List<Integer> input) {
            return input.size() >= 4;
        }

        @Override
        public List<SplittableTask<List<Integer>, Integer>> split(List<Integer> input, int targetParts) {
            loadBalancingConsidered = true;
            
            // Simulate load balancing by considering work distribution
            List<SplittableTask<List<Integer>, Integer>> tasks = new ArrayList<>();
            
            // Try to balance work based on actual values (load balancing)
            int totalWork = input.stream().mapToInt(Integer::intValue).sum();
            int targetWorkPerPart = totalWork / targetParts;
            
            int currentSum = 0;
            int startIdx = 0;
            
            for (int i = 0; i < input.size() && tasks.size() < targetParts - 1; i++) {
                currentSum += input.get(i);
                if (currentSum >= targetWorkPerPart) {
                    List<Integer> chunk = input.subList(startIdx, i + 1);
                    tasks.add(new TestSumSplittablePartTask(chunk));
                    startIdx = i + 1;
                    currentSum = 0;
                }
            }
            
            // Add remaining elements to last task
            if (startIdx < input.size()) {
                List<Integer> lastChunk = input.subList(startIdx, input.size());
                tasks.add(new TestSumSplittablePartTask(lastChunk));
            }
            
            return tasks;
        }

        @Override
        public Integer combineResults(List<Integer> splitResults, PipelineContext context) {
            return splitResults.stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public long estimateWorkSize(List<Integer> input) {
            return input.stream().mapToLong(Integer::longValue).sum();
        }

        public boolean wasLoadBalancingConsidered() {
            return loadBalancingConsidered;
        }
    }

    /**
     * Test splittable task with work stealing simulation
     */
    static class TestWorkStealingSplittableTask implements SplittableTask<List<Integer>, Integer> {
        private final AtomicInteger completedTasks = new AtomicInteger(0);

        @Override
        public CompletableFuture<Integer> execute(List<Integer> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                // Simulate variable processing time
                try {
                    Thread.sleep(input.size() * 10); // Longer for larger chunks
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                completedTasks.incrementAndGet();
                return input.stream().mapToInt(Integer::intValue).sum();
            });
        }

        @Override
        public boolean canSplit(List<Integer> input) {
            return input.size() >= 6;
        }

        @Override
        public List<SplittableTask<List<Integer>, Integer>> split(List<Integer> input, int targetParts) {
            List<SplittableTask<List<Integer>, Integer>> tasks = new ArrayList<>();
            
            // Create uneven splits to simulate work stealing scenarios
            int[] splitSizes = {input.size() / 2, input.size() / 3, input.size() - input.size() / 2 - input.size() / 3};
            
            int startIdx = 0;
            for (int i = 0; i < Math.min(targetParts, splitSizes.length); i++) {
                int endIdx = Math.min(startIdx + splitSizes[i], input.size());
                if (startIdx < endIdx) {
                    List<Integer> chunk = input.subList(startIdx, endIdx);
                    tasks.add(new TestWorkStealingPartTask(chunk));
                    startIdx = endIdx;
                }
            }
            
            return tasks;
        }

        @Override
        public Integer combineResults(List<Integer> splitResults, PipelineContext context) {
            return splitResults.stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public long estimateWorkSize(List<Integer> input) {
            return input.size();
        }

        public int getCompletedTaskCount() {
            return completedTasks.get();
        }
    }

    /**
     * Work stealing part task
     */
    static class TestWorkStealingPartTask implements SplittableTask<List<Integer>, Integer> {
        private final List<Integer> chunk;

        TestWorkStealingPartTask(List<Integer> chunk) {
            this.chunk = chunk;
        }

        @Override
        public CompletableFuture<Integer> execute(List<Integer> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                return chunk.stream().mapToInt(Integer::intValue).sum();
            });
        }

        @Override
        public boolean canSplit(List<Integer> input) {
            return chunk.size() >= 3; // Can be further split if large enough
        }

        @Override
        public List<SplittableTask<List<Integer>, Integer>> split(List<Integer> input, int targetParts) {
            if (!canSplit(input)) {
                throw new UnsupportedOperationException("Cannot split further");
            }
            
            // Split this chunk further for work stealing
            List<SplittableTask<List<Integer>, Integer>> tasks = new ArrayList<>();
            int halfSize = chunk.size() / 2;
            
            tasks.add(new TestSumSplittablePartTask(chunk.subList(0, halfSize)));
            tasks.add(new TestSumSplittablePartTask(chunk.subList(halfSize, chunk.size())));
            
            return tasks;
        }

        @Override
        public Integer combineResults(List<Integer> splitResults, PipelineContext context) {
            return splitResults.stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public long estimateWorkSize(List<Integer> input) {
            return chunk.size();
        }
    }

    /**
     * Test splittable task with metrics and optimization
     */
    static class TestMetricsSplittableTask implements SplittableTask<List<Integer>, Integer> {

        @Override
        public CompletableFuture<Integer> execute(List<Integer> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> 
                input.stream().mapToInt(Integer::intValue).sum()
            );
        }

        @Override
        public boolean canSplit(List<Integer> input) {
            return input.size() >= 4;
        }

        @Override
        public List<SplittableTask<List<Integer>, Integer>> split(List<Integer> input, int targetParts) {
            List<SplittableTask<List<Integer>, Integer>> tasks = new ArrayList<>();
            int chunkSize = input.size() / targetParts;
            
            for (int i = 0; i < targetParts; i++) {
                int start = i * chunkSize;
                int end = (i == targetParts - 1) ? input.size() : (i + 1) * chunkSize;
                tasks.add(new TestSumSplittablePartTask(input.subList(start, end)));
            }
            
            return tasks;
        }

        @Override
        public Integer combineResults(List<Integer> splitResults, PipelineContext context) {
            return splitResults.stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public long estimateWorkSize(List<Integer> input) {
            return input.size();
        }

        /**
         * Estimate the efficiency of splitting into given number of parts
         */
        public double estimateSplitEfficiency(List<Integer> input, int parts) {
            if (!canSplit(input) || parts <= 1) {
                return 0.0;
            }
            
            // Simple efficiency model based on parallelization benefit vs overhead
            double parallelBenefit = Math.min(parts, Runtime.getRuntime().availableProcessors()) / (double) parts;
            double overhead = 0.1 * parts; // Assume 10% overhead per part
            
            return Math.max(0.0, parallelBenefit - overhead);
        }

        /**
         * Recommend optimal number of splits based on input characteristics
         */
        public int recommendSplitCount(List<Integer> input) {
            if (!canSplit(input)) {
                return 1;
            }
            
            int availableCores = Runtime.getRuntime().availableProcessors();
            int maxUsefulSplits = Math.min(availableCores, input.size() / 2);
            
            return Math.max(2, Math.min(getMaximumSplitParts(), maxUsefulSplits));
        }
    }
}
