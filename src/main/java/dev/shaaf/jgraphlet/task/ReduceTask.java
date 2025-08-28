package dev.shaaf.jgraphlet.task;

import dev.shaaf.jgraphlet.pipeline.PipelineContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract base class for reduce operations that combine a list of inputs
 * into a single output value using an associative reduction function.
 * 
 * This follows the functional programming reduce pattern and is useful for:
 * - Aggregating data collections
 * - Computing summaries, totals, or statistics
 * - Combining parallel computation results
 *
 * @param <I> The input element type
 * @param <O> The output result type
 */
public abstract class ReduceTask<I, O> implements Task<List<I>, O> {
    
    /**
     * Combines two values into a single result.
     * This operation should be associative: reduce(reduce(a, b), c) == reduce(a, reduce(b, c))
     * and should be thread-safe as it may be called concurrently.
     * 
     * @param accumulator The accumulated result so far
     * @param next The next value to combine
     * @return The combined result
     */
    protected abstract O reduce(O accumulator, I next);
    
    /**
     * Provides the identity value for the reduction operation.
     * This is the starting value and should satisfy: reduce(identity(), x) == x
     * 
     * @return The identity value for the reduction
     */
    protected abstract O identity();
    
    /**
     * Executes the reduce operation on all input elements.
     * 
     * @param inputList The list of input elements to reduce
     * @param context The pipeline context
     * @return A future containing the reduced result
     */
    @Override
    public CompletableFuture<O> execute(List<I> inputList, PipelineContext context) {
        return CompletableFuture.supplyAsync(() -> {
            if (inputList.isEmpty()) {
                return identity();
            }
            
            O result = identity();
            for (I input : inputList) {
                result = reduce(result, input);
            }
            return result;
        });
    }
    
    /**
     * Parallel version of the reduce operation.
     * This uses a divide-and-conquer approach to reduce elements in parallel.
     * Only use this if the reduce operation is associative and commutative.
     * 
     * @param inputList The list of input elements to reduce
     * @param context The pipeline context
     * @return A future containing the reduced result
     */
    protected CompletableFuture<O> executeParallel(List<I> inputList, PipelineContext context) {
        return CompletableFuture.supplyAsync(() -> {
            if (inputList.isEmpty()) {
                return identity();
            }
            
            // Use parallel stream reduction
            return inputList.parallelStream()
                    .reduce(identity(), 
                           (acc, item) -> reduce(acc, item),
                           this::combineResults);
        });
    }
    
    /**
     * Combines two intermediate results from parallel reduction.
     * By default, this uses the same reduce function, but can be overridden
     * if combining results requires different logic.
     * 
     * @param result1 First intermediate result
     * @param result2 Second intermediate result
     * @return Combined result
     */
    protected O combineResults(O result1, O result2) {
        // For most cases, combining results is the same as reducing values
        // But we need to treat both as "next" values, so we reduce one into the other
        return reduce(result1, convertToInput(result2));
    }
    
    /**
     * Converts an output value back to an input value for combination.
     * This is needed when combining parallel results.
     * By default, assumes I and O are the same type, but override if needed.
     * 
     * @param output The output value to convert
     * @return The input representation
     */
    @SuppressWarnings("unchecked")
    protected I convertToInput(O output) {
        return (I) output;
    }
    
    /**
     * Indicates whether this reduce operation can be safely parallelized.
     * Override this to return true if the reduce function is associative,
     * commutative, and thread-safe.
     * 
     * @return true if parallel execution is safe
     */
    protected boolean supportsParallelExecution() {
        return false;
    }
    
    /**
     * Returns the minimum list size that justifies parallel execution.
     * Smaller lists should be processed sequentially to avoid overhead.
     * 
     * @return Minimum size for parallel processing
     */
    protected int getParallelThreshold() {
        return 1000;
    }
}
