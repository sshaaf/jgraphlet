package dev.shaaf.jgraphlet.task;

import dev.shaaf.jgraphlet.pipeline.PipelineContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract base class for map operations that transform a list of inputs
 * into a list of outputs by applying a transformation function to each element.
 * 
 * This follows the functional programming map pattern and is useful for:
 * - Transforming data collections
 * - Applying the same operation to multiple items
 * - Parallel processing of independent items
 *
 * @param <I> The input element type
 * @param <O> The output element type
 */
public abstract class MapTask<I, O> implements Task<List<I>, List<O>> {
    
    /**
     * Transforms a single input element to an output element.
     * This method should be pure (no side effects) and thread-safe
     * as it may be called concurrently for different elements.
     * 
     * @param input The input element to transform
     * @return The transformed output element
     */
    protected abstract O map(I input);
    
    /**
     * Executes the map operation on all input elements.
     * By default, this processes elements sequentially, but subclasses
     * can override to implement parallel processing.
     * 
     * @param inputList The list of input elements
     * @param context The pipeline context
     * @return A future containing the list of transformed elements
     */
    @Override
    public CompletableFuture<List<O>> execute(List<I> inputList, PipelineContext context) {
        return CompletableFuture.supplyAsync(() -> {
            List<O> results = new ArrayList<>(inputList.size());
            for (I input : inputList) {
                O output = map(input);
                results.add(output);
            }
            return results;
        });
    }
    
    /**
     * Parallel version of the map operation.
     * This processes elements concurrently using parallel streams.
     * 
     * @param inputList The list of input elements
     * @param context The pipeline context
     * @return A future containing the list of transformed elements
     */
    protected CompletableFuture<List<O>> executeParallel(List<I> inputList, PipelineContext context) {
        return CompletableFuture.supplyAsync(() -> 
            inputList.parallelStream()
                    .map(this::map)
                    .toList()
        );
    }
    
    /**
     * Indicates whether this map operation can be safely parallelized.
     * Override this to return true if the map function is thread-safe
     * and doesn't depend on processing order.
     * 
     * @return true if parallel execution is safe
     */
    protected boolean supportsParallelExecution() {
        return false;
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
