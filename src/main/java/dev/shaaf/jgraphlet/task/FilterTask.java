package dev.shaaf.jgraphlet.task;

import dev.shaaf.jgraphlet.pipeline.PipelineContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract base class for filter operations that select elements from
 * a list based on a predicate function.
 * 
 * This follows the functional programming filter pattern and is useful for:
 * - Selecting subsets of data based on criteria
 * - Removing unwanted elements from collections
 * - Data validation and cleanup
 *
 * @param <T> The element type
 */
public abstract class FilterTask<T> implements Task<List<T>, List<T>> {
    
    /**
     * Tests whether an element should be included in the result.
     * This method should be pure (no side effects) and thread-safe
     * as it may be called concurrently for different elements.
     * 
     * @param element The element to test
     * @return true if the element should be included
     */
    protected abstract boolean test(T element);
    
    /**
     * Executes the filter operation on all input elements.
     * 
     * @param inputList The list of input elements to filter
     * @param context The pipeline context
     * @return A future containing the filtered list
     */
    @Override
    public CompletableFuture<List<T>> execute(List<T> inputList, PipelineContext context) {
        return CompletableFuture.supplyAsync(() -> {
            List<T> results = new ArrayList<>();
            for (T element : inputList) {
                if (test(element)) {
                    results.add(element);
                }
            }
            return results;
        });
    }
    
    /**
     * Parallel version of the filter operation.
     * This processes elements concurrently using parallel streams.
     * 
     * @param inputList The list of input elements to filter
     * @param context The pipeline context
     * @return A future containing the filtered list
     */
    protected CompletableFuture<List<T>> executeParallel(List<T> inputList, PipelineContext context) {
        return CompletableFuture.supplyAsync(() -> 
            inputList.parallelStream()
                    .filter(this::test)
                    .toList()
        );
    }
    
    /**
     * Indicates whether this filter operation can be safely parallelized.
     * Override this to return true if the test function is thread-safe
     * and doesn't depend on processing order.
     * 
     * @return true if parallel execution is safe
     */
    protected boolean supportsParallelExecution() {
        return false;
    }
    
    /**
     * Returns the expected selectivity of the filter (ratio of elements kept).
     * This helps optimize memory allocation for the result list.
     * 
     * @return Expected selectivity between 0.0 (none kept) and 1.0 (all kept)
     */
    protected double getExpectedSelectivity() {
        return 0.5; // Default assumption: half the elements will be kept
    }
    
    /**
     * Returns the preferred batch size for processing elements.
     * Larger batches can improve throughput but may increase latency.
     * 
     * @return Preferred batch size for processing
     */
    protected int getPreferredBatchSize() {
        return 1000;
    }
}
