package dev.shaaf.jgraphlet.task;

import dev.shaaf.jgraphlet.pipeline.PipelineContext;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * A task that produces a stream of results that can be consumed incrementally.
 * This enables memory-efficient processing of large datasets by avoiding
 * the need to materialize all results in memory at once.
 * 
 * Streaming tasks are particularly useful for:
 * - Processing large files line by line
 * - Generating sequences of data
 * - Transforming data that doesn't fit in memory
 *
 * @param <I> The input type
 * @param <O> The output stream element type
 */
public interface StreamingTask<I, O> extends Task<I, Stream<O>> {
    
    /**
     * Executes the task and returns a stream of results.
     * The stream should be lazy and process data incrementally.
     * 
     * @param input The input to process
     * @param context The pipeline context
     * @return A future containing a stream of results
     */
    @Override
    CompletableFuture<Stream<O>> execute(I input, PipelineContext context);
    
    /**
     * Indicates whether the stream should be processed in parallel.
     * Parallel streams can improve performance but may affect ordering.
     * 
     * @return true if the stream can be processed in parallel
     */
    default boolean allowParallelStream() {
        return false;
    }
    
    /**
     * Indicates the expected size of the stream for optimization purposes.
     * This helps downstream tasks prepare appropriate buffer sizes.
     * 
     * @param input The input that will be processed
     * @return Expected stream size, or -1 if unknown
     */
    default long estimateStreamSize(I input) {
        return -1;
    }
    
    /**
     * Indicates whether the stream maintains ordering of elements.
     * This is important for tasks that depend on element order.
     * 
     * @return true if the stream maintains element ordering
     */
    default boolean isOrdered() {
        return true;
    }
}
