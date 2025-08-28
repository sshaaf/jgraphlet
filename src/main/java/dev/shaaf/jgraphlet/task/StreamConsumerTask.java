package dev.shaaf.jgraphlet.task;

import dev.shaaf.jgraphlet.pipeline.PipelineContext;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * A task that consumes a stream of input elements and produces a result.
 * This enables memory-efficient processing of large datasets by processing
 * elements incrementally rather than loading everything into memory.
 * 
 * Stream consumer tasks are useful for:
 * - Aggregating data from large streams
 * - Filtering and transforming streaming data
 * - Collecting results from streaming operations
 *
 * @param <I> The input stream element type
 * @param <O> The output type
 */
public interface StreamConsumerTask<I, O> extends Task<Stream<I>, O> {
    
    /**
     * Processes the input stream and produces a result.
     * This method should consume the stream incrementally to maintain
     * memory efficiency.
     * 
     * @param inputStream The stream of input elements
     * @param context The pipeline context
     * @return The processing result
     */
    O processStream(Stream<I> inputStream, PipelineContext context);
    
    /**
     * Default implementation that wraps processStream in a CompletableFuture.
     * 
     * @param input The input stream
     * @param context The pipeline context
     * @return A future containing the processing result
     */
    @Override
    default CompletableFuture<O> execute(Stream<I> input, PipelineContext context) {
        return CompletableFuture.supplyAsync(() -> processStream(input, context));
    }
    
    /**
     * Indicates the preferred batch size for processing stream elements.
     * This helps optimize memory usage and processing performance.
     * 
     * @return Preferred batch size, or -1 for no preference
     */
    default int getPreferredBatchSize() {
        return 1000;
    }
    
    /**
     * Indicates whether this consumer can handle parallel streams efficiently.
     * 
     * @return true if parallel streams are supported
     */
    default boolean supportsParallelStreams() {
        return true;
    }
    
    /**
     * Called when the input stream is exhausted or processing is complete.
     * This allows the consumer to perform cleanup or finalization.
     * 
     * @param context The pipeline context
     */
    default void onStreamComplete(PipelineContext context) {
        // Default implementation does nothing
    }
}
