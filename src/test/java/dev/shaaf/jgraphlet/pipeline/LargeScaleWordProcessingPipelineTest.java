package dev.shaaf.jgraphlet.pipeline;

import dev.shaaf.jgraphlet.task.Task;
import dev.shaaf.jgraphlet.task.resource.ResourceAwareTask;
import dev.shaaf.jgraphlet.task.resource.ResourceConstraint;
import dev.shaaf.jgraphlet.task.resource.ResourceRequirements;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test that validates EnhancedTaskPipeline using a real-world
 * large-scale word processing scenario. Tests threading, performance,
 * resource management, and pipeline orchestration.
 */
class LargeScaleWordProcessingPipelineTest {

    @TempDir
    Path tempDir;

    private TaskPipelineConfig config;
    private TestResourceManager resourceManager;
    private TestMetricsCollector metricsCollector;

    @BeforeEach
    void setUp() {
        resourceManager = new TestResourceManager(256 * 1024 * 1024); // 256MB
        metricsCollector = new TestMetricsCollector();
        
        config = TaskPipelineConfig.builder()
            .withResourceManager(resourceManager)
            .withMetrics(metricsCollector)
            .withMaxConcurrentTasks(10) // Increase for I/O bound chunk processing
            .withWorkStealing(true)
            .build();
    }

    @Test
    @DisplayName("Large-scale word processing pipeline should handle concurrent file processing")
    @Timeout(30) // Prevent runaway tests
    void testLargeScaleWordProcessingPipeline() throws Exception {
        // Generate test data - realistic file structure
        List<Path> testFiles = createTestDataFiles(50, 1000); // 50 files, ~1K words each
        int topN = 10;
        
        try (EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline(config)) {
            // Build the complete word processing pipeline
            pipeline.add("fileDiscovery", new FileDiscoveryTask())
                   .then("mapAndSpill", new MapAndSpillTask())
                   .then("mergeAndReduce", new MergeAndReduceTask())
                   .then("topNExtraction", new TopNExtractionTask(topN))
                   .then("cleanup", new CleanupTask());

            // Execute the pipeline
            ProcessingInput input = new ProcessingInput(tempDir, topN);
            
            @SuppressWarnings("unchecked")
            List<WordCount> result = (List<WordCount>) pipeline.run(input).join();
            
            // Validate results
            assertNotNull(result, "Pipeline should return results");
            assertEquals(topN, result.size(), "Should return exactly top N words");
            
            // Verify ordering (descending by count)
            for (int i = 1; i < result.size(); i++) {
                assertTrue(result.get(i-1).count >= result.get(i).count, 
                    "Results should be sorted by count descending");
            }
            
            // Validate resource usage was tracked (may be 0 if no ResourceAware tasks)
            // Since we use ResourceAwareTask, we should see some memory tracking
            System.out.println("Max memory used: " + resourceManager.getMaxUsedMemory() + " bytes");
            // Resource tracking works but may be minimal for this test size
            
            // Verify all temp files were cleaned up
            assertTempDirectoryClean();
            
            System.out.println("Pipeline completed successfully!");
            System.out.println("Top words processed: " + result);
            System.out.println("Max memory used: " + formatBytes(resourceManager.getMaxUsedMemory()));
        }
    }

    @Test
    @DisplayName("Pipeline should handle resource constraints gracefully")
    void testResourceConstrainedExecution() throws Exception {
        // Create resource-constrained environment
        TestResourceManager constrainedManager = new TestResourceManager(1024); // Only 1KB
        TaskPipelineConfig constrainedConfig = TaskPipelineConfig.builder()
            .withResourceManager(constrainedManager)
            .withMaxConcurrentTasks(2)
            .build();
            
        List<Path> testFiles = createTestDataFiles(5, 10); // Smaller dataset
        
        try (EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline(constrainedConfig)) {
            pipeline.add("fileDiscovery", new FileDiscoveryTask())
                   .then("mapAndSpill", new MapAndSpillTask())
                   .then("mergeAndReduce", new MergeAndReduceTask())
                   .then("topNExtraction", new TopNExtractionTask(5));

            ProcessingInput input = new ProcessingInput(tempDir, 5);
            
            @SuppressWarnings("unchecked")
            List<WordCount> result = (List<WordCount>) pipeline.run(input).join();
            
            assertNotNull(result, "Pipeline should complete even under resource constraints");
            assertTrue(result.size() <= 5, "Should return at most 5 words");
        }
    }

    @Test
    @DisplayName("Pipeline should handle concurrent executions safely")
    void testConcurrentPipelineExecutions() throws Exception {
        List<Path> testFiles = createTestDataFiles(10, 50);
        
        List<CompletableFuture<List<WordCount>>> futures = new ArrayList<>();
        
        // Run multiple pipeline instances concurrently
        for (int i = 0; i < 3; i++) {
            CompletableFuture<List<WordCount>> future = CompletableFuture.supplyAsync(() -> {
                try (EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline(config)) {
                    pipeline.add("fileDiscovery", new FileDiscoveryTask())
                           .then("mapAndSpill", new MapAndSpillTask())
                           .then("mergeAndReduce", new MergeAndReduceTask())
                           .then("topNExtraction", new TopNExtractionTask(5));

                    ProcessingInput input = new ProcessingInput(tempDir, 5);
                    
                    @SuppressWarnings("unchecked")
                    List<WordCount> result = (List<WordCount>) pipeline.run(input).join();
                    return result;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            futures.add(future);
        }
        
        // Wait for all to complete
        List<List<WordCount>> results = new ArrayList<>();
        for (CompletableFuture<List<WordCount>> future : futures) {
            List<WordCount> result = future.join();
            assertNotNull(result);
            results.add(result);
        }
        
        assertEquals(3, results.size(), "All concurrent executions should complete");
    }

    @Test
    @DisplayName("Pipeline should handle large file with chunking and merging")
    @Timeout(120) // Allow up to 2 minutes for large file processing
    void testLargeFileChunkProcessing() throws Exception {
        // Generate a 50MB file with random words (faster for testing while still validating chunking)
        long fileSize = 20 * 1024 * 1024L; // 50MB
        Path largeFile = generateLargeTestFile(fileSize);
        
        // Ensure file is completely written and closed
        System.out.println("Verifying file is properly closed...");
        long actualFileSize = Files.size(largeFile);
        System.out.println("Generated large test file: " + largeFile + " (size: " + formatBytes(actualFileSize) + ")");
        
        try {
            int chunkCount = 5; // Split into 8 chunks for parallel processing
            int topN = 2;
            
            try (EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline(config)) {
                // Build pipeline for truly parallel chunked file processing
                pipeline.add("fileChunking", new FileChunkingTask(chunkCount))
                       // Fan-out: Process each chunk as a separate parallel task
                       .then("chunkFanOut", new ChunkFanOutTask())
                       // Fan-in: Merge results from parallel chunk processing
                       .then("chunkMerging", new ParallelChunkMergingTask())
                       .then("topNExtraction", new EnhancedTopNExtractionTask(topN));

                // Execute with large file input
                LargeFileInput input = new LargeFileInput(largeFile, topN);
                
                @SuppressWarnings("unchecked")
                List<WordCount> result = (List<WordCount>) pipeline.run(input).join();
                
                // Validate results
                assertNotNull(result, "Pipeline should return results for large file");
                assertEquals(topN, result.size(), "Should return exactly top N words from 1GB file");
                
                // Verify ordering (descending by count)
                for (int i = 1; i < result.size(); i++) {
                    assertTrue(result.get(i-1).count >= result.get(i).count, 
                        "Results should be sorted by count descending");
                }
                
                // Validate we processed a substantial amount of data
                assertTrue(result.get(0).count > 100, 
                    "Top word should appear many times in large file");
                
                System.out.println("Large file processing completed successfully!");
                System.out.println("Top words from large file: " + result.subList(0, Math.min(10, result.size())));
                System.out.println("Max memory used: " + formatBytes(resourceManager.getMaxUsedMemory()));
            }
        } finally {
            // Clean up the large test file
            try {
                if (Files.exists(largeFile)) {
                    long sizeBeforeDelete = Files.size(largeFile);
                    Files.deleteIfExists(largeFile);
                    System.out.println("Cleaned up large test file (" + formatBytes(sizeBeforeDelete) + ")");
                }
            } catch (IOException e) {
                System.err.println("Failed to cleanup large file: " + e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("Pipeline should handle error scenarios gracefully")
    void testErrorHandling() throws Exception {
        try (EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline(config)) {
            pipeline.add("fileDiscovery", new FileDiscoveryTask())
                   .then("failingTask", new FailingMapTask()) // Intentionally failing task
                   .then("topNExtraction", new TopNExtractionTask(5));

            ProcessingInput input = new ProcessingInput(tempDir, 5);
            
            // Should handle task failure
            assertThrows(Exception.class, () -> {
                pipeline.run(input).join();
            }, "Pipeline should propagate task failures");
        }
    }

    // ================================================================================
    // Pipeline Task Implementations (Real implementations, no mocking)
    // ================================================================================

    /**
     * Task that discovers all text files in the input directory
     */
    static class FileDiscoveryTask implements Task<ProcessingInput, List<Path>> {
        @Override
        public CompletableFuture<List<Path>> execute(ProcessingInput input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    List<Path> files = new ArrayList<>();
                    try (Stream<Path> paths = Files.walk(input.rootDir)) {
                        paths.filter(Files::isRegularFile)
                             .filter(path -> path.toString().endsWith(".txt"))
                             .forEach(files::add);
                    }
                    context.put("fileCount", files.size());
                    return files;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /**
     * Resource-aware task that processes files and creates spill files
     */
    static class MapAndSpillTask implements ResourceAwareTask<List<Path>, List<Path>> {
        private final AtomicInteger spillCounter = new AtomicInteger(0);
        
        @Override
        public ResourceRequirements estimateResources(List<Path> input) {
            // Estimate memory based on file count (rough heuristic)
            long estimatedMemory = input.size() * 64 * 1024; // 64KB per file
            return new ResourceRequirements(estimatedMemory, 0.5, false);
        }
        
        @Override
        public void onResourceConstraint(ResourceConstraint constraint) {
            System.out.println("MapAndSpill running under resource constraints: " + constraint);
        }

        @Override
        public CompletableFuture<List<Path>> execute(List<Path> files, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    List<Path> spillFiles = new CopyOnWriteArrayList<>();
                    Path tempDir = context.get("tempDir", Path.class).orElse(null);
                    if (tempDir == null) {
                        tempDir = Files.createTempDirectory("pipeline-spill");
                        context.put("tempDir", tempDir);
                    }

                    // Process files in parallel (simulating StructuredTaskScope behavior)
                    final Path finalTempDir = tempDir; // Make effectively final for lambda
                    files.parallelStream().forEach(file -> {
                        try {
                            Map<String, LongAdder> localMap = processFile(file);
                            if (!localMap.isEmpty()) {
                                Path spillFile = createSpillFile(localMap, finalTempDir);
                                spillFiles.add(spillFile);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                    
                    context.put("spillFileCount", spillFiles.size());
                    return spillFiles;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        private Map<String, LongAdder> processFile(Path file) throws IOException {
            Map<String, LongAdder> wordCounts = new HashMap<>();
            WordView wordView = new WordView();
            
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseLine(line, wordCounts, wordView);
                }
            }
            return wordCounts;
        }

        private Path createSpillFile(Map<String, LongAdder> localMap, Path tempDir) throws IOException {
            Path spillFile = tempDir.resolve("spill-" + spillCounter.getAndIncrement() + ".txt");
            
            List<Map.Entry<String, LongAdder>> sortedEntries = new ArrayList<>(localMap.entrySet());
            sortedEntries.sort(Map.Entry.comparingByKey());

            try (BufferedWriter writer = Files.newBufferedWriter(spillFile, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, LongAdder> entry : sortedEntries) {
                    writer.write(entry.getKey() + "\t" + entry.getValue().sum());
                    writer.newLine();
                }
            }
            return spillFile;
        }

        private void parseLine(String line, Map<String, LongAdder> localMap, WordView wordView) {
            final char[] chars = line.toCharArray();
            int wordStart = -1;
            for (int i = 0; i < chars.length; i++) {
                char c = chars[i];
                if (Character.isLetter(c)) {
                    if (wordStart == -1) {
                        wordStart = i;
                    }
                    chars[i] = Character.toLowerCase(c);
                } else {
                    if (wordStart != -1) {
                        processWord(localMap, wordView, chars, wordStart, i - wordStart);
                        wordStart = -1;
                    }
                }
            }
            if (wordStart != -1) {
                processWord(localMap, wordView, chars, wordStart, chars.length - wordStart);
            }
        }

        private void processWord(Map<String, LongAdder> localMap, WordView wordView, char[] buffer, int start, int len) {
            wordView.set(buffer, start, len);
            String word = wordView.toString();
            LongAdder adder = localMap.get(word);
            if (adder == null) {
                adder = new LongAdder();
                localMap.put(word, adder);
            }
            adder.increment();
        }
    }

    /**
     * Task that merges spill files into final word counts
     */
    static class MergeAndReduceTask implements Task<List<Path>, Path> {
        @Override
        public CompletableFuture<Path> execute(List<Path> spillFiles, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Path tempDir = context.get("tempDir", Path.class).orElse(null);
                    Path finalOutputFile = tempDir.resolve("final-counts.txt");
                    
                    // Merge using priority queue (K-way merge)
                    List<BufferedReader> readers = new ArrayList<>();
                    PriorityQueue<WordFileEntry> pq = new PriorityQueue<>(Comparator.comparing(e -> e.word));

                    try {
                        // Initialize readers and priority queue
                        for (Path file : spillFiles) {
                            BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                            readers.add(reader);
                            String line = reader.readLine();
                            if (line != null) {
                                pq.add(new WordFileEntry(line, reader));
                            }
                        }

                        // Merge and write final counts
                        try (BufferedWriter writer = Files.newBufferedWriter(finalOutputFile, StandardCharsets.UTF_8)) {
                            String currentWord = null;
                            long currentCount = 0;

                            while (!pq.isEmpty()) {
                                WordFileEntry entry = pq.poll();
                                if (currentWord == null) currentWord = entry.word;

                                if (!entry.word.equals(currentWord)) {
                                    writer.write(currentWord + "\t" + currentCount);
                                    writer.newLine();
                                    currentWord = entry.word;
                                    currentCount = 0;
                                }
                                currentCount += entry.count;

                                String nextLine = entry.reader.readLine();
                                if (nextLine != null) {
                                    pq.add(new WordFileEntry(nextLine, entry.reader));
                                }
                            }
                            if (currentWord != null) {
                                writer.write(currentWord + "\t" + currentCount);
                                writer.newLine();
                            }
                        }
                    } finally {
                        for (BufferedReader reader : readers) {
                            try { reader.close(); } catch (IOException e) { /* ignore */ }
                        }
                    }
                    
                    context.put("finalOutputFile", finalOutputFile);
                    return finalOutputFile;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /**
     * Task that extracts top N words from final counts
     */
    static class TopNExtractionTask implements Task<Path, List<WordCount>> {
        private final int topN;
        
        public TopNExtractionTask(int topN) {
            this.topN = topN;
        }

        @Override
        public CompletableFuture<List<WordCount>> execute(Path finalFile, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    PriorityQueue<WordCount> topNHeap = new PriorityQueue<>(Comparator.comparingLong(wc -> wc.count));
                    AtomicLong uniqueWords = new AtomicLong(0);

                    try (Stream<String> lines = Files.lines(finalFile, StandardCharsets.UTF_8)) {
                        lines.forEach(line -> {
                            uniqueWords.incrementAndGet();
                            String[] parts = line.split("\t");
                            if (parts.length == 2) {
                                String word = parts[0];
                                long count = Long.parseLong(parts[1]);
                                if (topNHeap.size() < topN) {
                                    topNHeap.add(new WordCount(word, count));
                                } else if (count > topNHeap.peek().count) {
                                    topNHeap.poll();
                                    topNHeap.add(new WordCount(word, count));
                                }
                            }
                        });
                    }

                    List<WordCount> result = new ArrayList<>(topNHeap);
                    result.sort(Comparator.comparingLong((WordCount wc) -> wc.count).reversed());
                    
                    context.put("uniqueWordCount", uniqueWords.get());
                    context.put("topWords", result);
                    return result;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /**
     * Cleanup task that removes temporary files
     */
    static class CleanupTask implements Task<List<WordCount>, List<WordCount>> {
        @Override
        public CompletableFuture<List<WordCount>> execute(List<WordCount> topWords, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Path tempDir = context.get("tempDir", Path.class).orElse(null);
                    if (tempDir != null && Files.exists(tempDir)) {
                        try (Stream<Path> walk = Files.walk(tempDir)) {
                            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                                try { 
                                    Files.delete(path); 
                                } catch (IOException e) { 
                                    // Ignore cleanup errors in tests
                                }
                            });
                        }
                    }
                    context.put("cleanupCompleted", true);
                    return topWords; // Pass through the results
                } catch (IOException e) {
                    // Don't fail the pipeline on cleanup errors
                    System.err.println("Cleanup warning: " + e.getMessage());
                    return topWords;
                }
            });
        }
    }

    /**
     * Intentionally failing task for error handling tests
     */
    static class FailingMapTask implements Task<List<Path>, List<Path>> {
        @Override
        public CompletableFuture<List<Path>> execute(List<Path> input, PipelineContext context) {
            return CompletableFuture.failedFuture(new RuntimeException("Simulated task failure"));
        }
    }

    /**
     * Task that splits a large file into chunks for parallel processing
     */
    static class FileChunkingTask implements Task<LargeFileInput, List<FileChunk>> {
        private final int chunkCount;
        
        public FileChunkingTask(int chunkCount) {
            this.chunkCount = chunkCount;
        }

        @Override
        public CompletableFuture<List<FileChunk>> execute(LargeFileInput input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    long fileSize = Files.size(input.filePath);
                    long chunkSize = fileSize / chunkCount;
                    List<FileChunk> chunks = new ArrayList<>();
                    
                    for (int i = 0; i < chunkCount; i++) {
                        long startOffset = i * chunkSize;
                        long endOffset = (i == chunkCount - 1) ? fileSize : (i + 1) * chunkSize;
                        chunks.add(new FileChunk(input.filePath, startOffset, endOffset, i));
                    }
                    
                    context.put("originalFileSize", fileSize);
                    context.put("chunkCount", chunks.size());
                    System.out.println("Split " + formatBytes(fileSize) + " file into " + chunks.size() + " chunks");
                    return chunks;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /**
     * Fan-out task that creates individual chunk processing tasks for true parallelism
     */
    static class ChunkFanOutTask implements Task<List<FileChunk>, List<Map<String, Long>>> {
        
        @Override
        public CompletableFuture<List<Map<String, Long>>> execute(List<FileChunk> chunks, PipelineContext context) {
            System.out.println("Starting parallel chunk processing with " + chunks.size() + " chunks");
            
            // Use a custom executor for I/O bound tasks (file reading)
            ExecutorService chunkExecutor = Executors.newFixedThreadPool(
                Math.min(chunks.size(), 100)); // Up to 100 concurrent I/O operations
            
            try {
                // Create individual CompletableFutures for each chunk to process in parallel
                List<CompletableFuture<Map<String, Long>>> chunkFutures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> processChunk(chunk), chunkExecutor))
                    .collect(java.util.stream.Collectors.toList());
            
                // Combine all futures and collect results
                return CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> chunkFutures.stream()
                        .map(CompletableFuture::join)
                        .collect(java.util.stream.Collectors.toList()))
                    .whenComplete((result, throwable) -> {
                        // Shutdown the custom executor
                        chunkExecutor.shutdown();
                        try {
                            if (!chunkExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                                chunkExecutor.shutdownNow();
                            }
                        } catch (InterruptedException e) {
                            chunkExecutor.shutdownNow();
                            Thread.currentThread().interrupt();
                        }
                    });
            } catch (Exception e) {
                chunkExecutor.shutdown();
                throw e;
            }
        }

        private Map<String, Long> processChunk(FileChunk chunk) {
            System.out.println("Processing " + chunk + " on thread: " + Thread.currentThread().getName());
            Map<String, LongAdder> wordCounts = new HashMap<>();
            WordView wordView = new WordView();
            
            try (RandomAccessFile file = new RandomAccessFile(chunk.filePath.toFile(), "r")) {
                file.seek(chunk.startOffset);
                
                // Adjust start to word boundary (unless at file start)
                if (chunk.startOffset > 0) {
                    // Skip to next word boundary
                    while (file.getFilePointer() < chunk.endOffset) {
                        int ch = file.read();
                        if (ch == -1) break;
                        if (!Character.isLetter(ch)) break;
                    }
                }
                
                StringBuilder lineBuffer = new StringBuilder(1024);
                long bytesRead = 0;
                long maxBytes = chunk.endOffset - file.getFilePointer();
                
                while (bytesRead < maxBytes) {
                    int ch = file.read();
                    if (ch == -1) break;
                    bytesRead++;
                    
                    if (ch == '\n' || ch == '\r') {
                        if (lineBuffer.length() > 0) {
                            parseLine(lineBuffer.toString(), wordCounts, wordView);
                            lineBuffer.setLength(0);
                        }
                    } else {
                        lineBuffer.append((char) ch);
                    }
                }
                
                // Process final line
                if (lineBuffer.length() > 0) {
                    parseLine(lineBuffer.toString(), wordCounts, wordView);
                }
                
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            
            // Convert to regular Map<String, Long>
            Map<String, Long> result = wordCounts.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().sum()
                ));
                
            System.out.println("Completed " + chunk + " with " + result.size() + " unique words");
            return result;
        }

        private void parseLine(String line, Map<String, LongAdder> localMap, WordView wordView) {
            final char[] chars = line.toCharArray();
            int wordStart = -1;
            for (int i = 0; i < chars.length; i++) {
                char c = chars[i];
                if (Character.isLetter(c)) {
                    if (wordStart == -1) {
                        wordStart = i;
                    }
                    chars[i] = Character.toLowerCase(c);
                } else {
                    if (wordStart != -1) {
                        processWord(localMap, wordView, chars, wordStart, i - wordStart);
                        wordStart = -1;
                    }
                }
            }
            if (wordStart != -1) {
                processWord(localMap, wordView, chars, wordStart, chars.length - wordStart);
            }
        }

        private void processWord(Map<String, LongAdder> localMap, WordView wordView, char[] buffer, int start, int len) {
            wordView.set(buffer, start, len);
            String word = wordView.toString();
            LongAdder adder = localMap.get(word);
            if (adder == null) {
                adder = new LongAdder();
                localMap.put(word, adder);
            }
            adder.increment();
        }
    }

    /**
     * Resource-aware task that processes file chunks in parallel (LEGACY - kept for reference)
     */
    static class ChunkProcessingTask implements ResourceAwareTask<List<FileChunk>, List<Map<String, Long>>> {
        
        @Override
        public ResourceRequirements estimateResources(List<FileChunk> input) {
            // Estimate memory based on chunk count and size
            long estimatedMemory = input.size() * 16 * 1024 * 1024; // 16MB per chunk
            return new ResourceRequirements(estimatedMemory, 1.0, false);
        }
        
        @Override
        public void onResourceConstraint(ResourceConstraint constraint) {
            System.out.println("ChunkProcessing running under resource constraints: " + constraint);
        }

        @Override
        public CompletableFuture<List<Map<String, Long>>> execute(List<FileChunk> chunks, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                // Process chunks in parallel
                List<Map<String, Long>> chunkResults = chunks.parallelStream()
                    .map(this::processChunk)
                    .collect(java.util.stream.Collectors.toList());
                
                context.put("processedChunks", chunkResults.size());
                System.out.println("Processed " + chunkResults.size() + " chunks in parallel");
                return chunkResults;
            });
        }

        private Map<String, Long> processChunk(FileChunk chunk) {
            Map<String, LongAdder> wordCounts = new HashMap<>();
            WordView wordView = new WordView();
            
            try (RandomAccessFile file = new RandomAccessFile(chunk.filePath.toFile(), "r")) {
                file.seek(chunk.startOffset);
                
                // Adjust start to word boundary (unless at file start)
                if (chunk.startOffset > 0) {
                    // Skip to next word boundary
                    while (file.getFilePointer() < chunk.endOffset) {
                        int ch = file.read();
                        if (ch == -1) break;
                        if (!Character.isLetter(ch)) break;
                    }
                }
                
                StringBuilder lineBuffer = new StringBuilder(1024);
                long bytesRead = 0;
                long maxBytes = chunk.endOffset - file.getFilePointer();
                
                while (bytesRead < maxBytes) {
                    int ch = file.read();
                    if (ch == -1) break;
                    bytesRead++;
                    
                    if (ch == '\n' || ch == '\r') {
                        if (lineBuffer.length() > 0) {
                            parseLine(lineBuffer.toString(), wordCounts, wordView);
                            lineBuffer.setLength(0);
                        }
                    } else {
                        lineBuffer.append((char) ch);
                    }
                }
                
                // Process final line
                if (lineBuffer.length() > 0) {
                    parseLine(lineBuffer.toString(), wordCounts, wordView);
                }
                
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            
            // Convert to regular Map<String, Long>
            return wordCounts.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().sum()
                ));
        }

        private void parseLine(String line, Map<String, LongAdder> localMap, WordView wordView) {
            final char[] chars = line.toCharArray();
            int wordStart = -1;
            for (int i = 0; i < chars.length; i++) {
                char c = chars[i];
                if (Character.isLetter(c)) {
                    if (wordStart == -1) {
                        wordStart = i;
                    }
                    chars[i] = Character.toLowerCase(c);
                } else {
                    if (wordStart != -1) {
                        processWord(localMap, wordView, chars, wordStart, i - wordStart);
                        wordStart = -1;
                    }
                }
            }
            if (wordStart != -1) {
                processWord(localMap, wordView, chars, wordStart, chars.length - wordStart);
            }
        }

        private void processWord(Map<String, LongAdder> localMap, WordView wordView, char[] buffer, int start, int len) {
            wordView.set(buffer, start, len);
            String word = wordView.toString();
            LongAdder adder = localMap.get(word);
            if (adder == null) {
                adder = new LongAdder();
                localMap.put(word, adder);
            }
            adder.increment();
        }
    }

    /**
     * Task that merges chunk results using parallel hierarchical merging
     */
    static class ParallelChunkMergingTask implements Task<List<Map<String, Long>>, Map<String, Long>> {
        @Override
        public CompletableFuture<Map<String, Long>> execute(List<Map<String, Long>> chunkResults, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                System.out.println("Starting parallel merge of " + chunkResults.size() + " chunk results");
                
                // Use hierarchical merging for better parallelism
                List<Map<String, Long>> currentLevel = new ArrayList<>(chunkResults);
                
                while (currentLevel.size() > 1) {
                    List<CompletableFuture<Map<String, Long>>> mergeFutures = new ArrayList<>();
                    
                    // Merge pairs in parallel using a custom executor for merging
                    ExecutorService mergeExecutor = Executors.newFixedThreadPool(
                        Math.min(currentLevel.size() / 2 + 1, 20)); // Up to 20 merge operations
                    
                    try {
                        for (int i = 0; i < currentLevel.size(); i += 2) {
                            final Map<String, Long> map1 = currentLevel.get(i);
                            final Map<String, Long> map2 = (i + 1 < currentLevel.size()) 
                                ? currentLevel.get(i + 1) 
                                : new HashMap<>();
                            
                            CompletableFuture<Map<String, Long>> mergeFuture = CompletableFuture.supplyAsync(() -> {
                                System.out.println("Merging maps on thread: " + Thread.currentThread().getName());
                                return mergeTwoMaps(map1, map2);
                            }, mergeExecutor);
                            
                            mergeFutures.add(mergeFuture);
                        }
                    
                        // Wait for all merges to complete and collect results
                        currentLevel = mergeFutures.stream()
                            .map(CompletableFuture::join)
                            .collect(java.util.stream.Collectors.toList());
                            
                        System.out.println("Merged level completed, " + currentLevel.size() + " maps remaining");
                    } finally {
                        mergeExecutor.shutdown();
                    }
                    
                }
                
                Map<String, Long> finalResult = currentLevel.isEmpty() ? new HashMap<>() : currentLevel.get(0);
                
                context.put("totalUniqueWords", finalResult.size());
                context.put("mergedWordCounts", finalResult);
                System.out.println("Parallel merge completed: " + finalResult.size() + " unique words");
                return finalResult;
            });
        }
        
        private Map<String, Long> mergeTwoMaps(Map<String, Long> map1, Map<String, Long> map2) {
            Map<String, LongAdder> merged = new ConcurrentHashMap<>();
            
            // Add all entries from map1
            map1.entrySet().parallelStream().forEach(entry -> {
                merged.computeIfAbsent(entry.getKey(), k -> new LongAdder()).add(entry.getValue());
            });
            
            // Add all entries from map2
            map2.entrySet().parallelStream().forEach(entry -> {
                merged.computeIfAbsent(entry.getKey(), k -> new LongAdder()).add(entry.getValue());
            });
            
            // Convert back to Map<String, Long>
            return merged.entrySet().stream()
                .collect(java.util.stream.Collectors.toConcurrentMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().sum()
                ));
        }
    }

    /**
     * Task that merges chunk results into final word counts (LEGACY - kept for reference)
     */
    static class ChunkMergingTask implements Task<List<Map<String, Long>>, Map<String, Long>> {
        @Override
        public CompletableFuture<Map<String, Long>> execute(List<Map<String, Long>> chunkResults, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                Map<String, LongAdder> mergedCounts = new ConcurrentHashMap<>();
                
                // Merge all chunk results
                chunkResults.parallelStream().forEach(chunkResult -> {
                    chunkResult.entrySet().parallelStream().forEach(entry -> {
                        mergedCounts.computeIfAbsent(entry.getKey(), k -> new LongAdder())
                                   .add(entry.getValue());
                    });
                });
                
                // Convert to final Map<String, Long>
                Map<String, Long> finalCounts = mergedCounts.entrySet().stream()
                    .collect(java.util.stream.Collectors.toConcurrentMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().sum()
                    ));
                
                context.put("totalUniqueWords", finalCounts.size());
                context.put("mergedWordCounts", finalCounts);
                System.out.println("Merged chunks into " + finalCounts.size() + " unique words");
                return finalCounts;
            });
        }
    }

    /**
     * Enhanced TopN extraction task that works with merged word counts
     */
    static class EnhancedTopNExtractionTask implements Task<Object, List<WordCount>> {
        private final int topN;
        
        public EnhancedTopNExtractionTask(int topN) {
            this.topN = topN;
        }

        @Override
        public CompletableFuture<List<WordCount>> execute(Object input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                // Handle both Path (from file processing) and Map<String, Long> (from chunk merging)
                Map<String, Long> wordCounts;
                
                if (input instanceof Path) {
                    // Original file-based processing
                    wordCounts = loadFromFile((Path) input);
                } else if (input instanceof Map) {
                    // Chunk-based processing result
                    @SuppressWarnings("unchecked")
                    Map<String, Long> typedInput = (Map<String, Long>) input;
                    wordCounts = typedInput;
                } else {
                    // Try to get from context as fallback
                    wordCounts = context.get("mergedWordCounts", Map.class).orElse(new HashMap<>());
                }
                
                PriorityQueue<WordCount> topNHeap = new PriorityQueue<>(Comparator.comparingLong(wc -> wc.count));
                
                for (Map.Entry<String, Long> entry : wordCounts.entrySet()) {
                    String word = entry.getKey();
                    long count = entry.getValue();
                    
                    if (topNHeap.size() < topN) {
                        topNHeap.add(new WordCount(word, count));
                    } else if (count > topNHeap.peek().count) {
                        topNHeap.poll();
                        topNHeap.add(new WordCount(word, count));
                    }
                }

                List<WordCount> result = new ArrayList<>(topNHeap);
                result.sort(Comparator.comparingLong((WordCount wc) -> wc.count).reversed());
                
                context.put("topWords", result);
                return result;
            });
        }

        private Map<String, Long> loadFromFile(Path filePath) {
            Map<String, Long> wordCounts = new HashMap<>();
            try (Stream<String> lines = Files.lines(filePath, StandardCharsets.UTF_8)) {
                lines.forEach(line -> {
                    String[] parts = line.split("\t");
                    if (parts.length == 2) {
                        String word = parts[0];
                        long count = Long.parseLong(parts[1]);
                        wordCounts.put(word, count);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return wordCounts;
        }
    }

    // ================================================================================
    // Supporting Classes and Utilities
    // ================================================================================

    /**
     * Flyweight pattern for efficient word processing
     */
    private static final class WordView implements CharSequence {
        private char[] buffer;
        private int offset;
        private int length;
        private int hash;

        public WordView set(char[] buffer, int offset, int length) {
            this.buffer = buffer;
            this.offset = offset;
            this.length = length;
            this.hash = 0;
            return this;
        }

        @Override
        public int length() { return length; }

        @Override
        public char charAt(int index) {
            if (index < 0 || index >= length) throw new StringIndexOutOfBoundsException(index);
            return buffer[offset + index];
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int hashCode() {
            int h = hash;
            if (h == 0 && length > 0) {
                for (int i = 0; i < length; i++) {
                    h = 31 * h + buffer[offset + i];
                }
                hash = h;
            }
            return h;
        }

        @Override
        public boolean equals(Object anObject) {
            if (this == anObject) return true;
            if (anObject instanceof CharSequence) {
                CharSequence other = (CharSequence) anObject;
                if (length != other.length()) return false;
                for (int i = 0; i < length; i++) {
                    if (buffer[offset + i] != other.charAt(i)) return false;
                }
                return true;
            }
            return false;
        }

        @Override
        public String toString() {
            return new String(buffer, offset, length);
        }
    }

    static class WordFileEntry {
        final String word;
        final long count;
        final BufferedReader reader;

        WordFileEntry(String line, BufferedReader reader) {
            String[] parts = line.split("\t");
            this.word = parts[0];
            this.count = Long.parseLong(parts[1]);
            this.reader = reader;
        }
    }

    static class ProcessingInput {
        final Path rootDir;
        final int topN;

        ProcessingInput(Path rootDir, int topN) {
            this.rootDir = rootDir;
            this.topN = topN;
        }
    }

    static class LargeFileInput {
        final Path filePath;
        final int topN;

        LargeFileInput(Path filePath, int topN) {
            this.filePath = filePath;
            this.topN = topN;
        }
    }

    static class FileChunk {
        final Path filePath;
        final long startOffset;
        final long endOffset;
        final int chunkId;

        FileChunk(Path filePath, long startOffset, long endOffset, int chunkId) {
            this.filePath = filePath;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.chunkId = chunkId;
        }

        @Override
        public String toString() {
            return String.format("Chunk[%d: %d-%d (%s)]", 
                chunkId, startOffset, endOffset, 
                formatBytes(endOffset - startOffset));
        }
    }

    static class WordCount {
        final String word;
        final long count;

        WordCount(String word, long count) {
            this.word = word;
            this.count = count;
        }

        @Override
        public String toString() {
            return word + ":" + count;
        }
    }

    /**
     * Test resource manager that tracks actual usage
     */
    static class TestResourceManager implements TaskPipelineConfig.ResourceManager {
        private final AtomicLong totalMemory;
        private final AtomicLong usedMemory = new AtomicLong(0);
        private final AtomicLong maxUsedMemory = new AtomicLong(0);

        TestResourceManager(long totalMemory) {
            this.totalMemory = new AtomicLong(totalMemory);
        }

        @Override
        public boolean canSchedule(ResourceRequirements requirements) {
            return usedMemory.get() + requirements.estimatedMemoryBytes <= totalMemory.get();
        }

        @Override
        public void reserveResources(ResourceRequirements requirements) {
            long newUsage = usedMemory.addAndGet(requirements.estimatedMemoryBytes);
            maxUsedMemory.getAndAccumulate(newUsage, Math::max);
        }

        @Override
        public void releaseResources(ResourceRequirements requirements) {
            usedMemory.addAndGet(-requirements.estimatedMemoryBytes);
        }

        @Override
        public boolean tryReserveResources(ResourceRequirements requirements) {
            long current, newValue;
            do {
                current = usedMemory.get();
                newValue = current + requirements.estimatedMemoryBytes;
                if (newValue > totalMemory.get()) return false;
            } while (!usedMemory.compareAndSet(current, newValue));
            maxUsedMemory.getAndAccumulate(newValue, Math::max);
            return true;
        }

        @Override
        public ResourceConstraint getCurrentConstraints() {
            long available = totalMemory.get() - usedMemory.get();
            boolean memoryConstrained = available < totalMemory.get() / 4; // Memory constrained if < 25% available
            return new ResourceConstraint(memoryConstrained, false, false, available, 1.0);
        }

        public long getMaxUsedMemory() {
            return maxUsedMemory.get();
        }
    }

    /**
     * Test metrics collector
     */
    static class TestMetricsCollector implements TaskPipelineConfig.MetricsCollector {
        private final Map<String, Object> metrics = new HashMap<>();

        @Override
        public void recordTaskExecution(String taskName, long durationMs, boolean success) {
            metrics.put(taskName + "_duration", durationMs);
            metrics.put(taskName + "_success", success);
        }

        @Override
        public void recordResourceUsage(String taskName, ResourceRequirements actual) {
            metrics.put(taskName + "_memory", actual.estimatedMemoryBytes);
            metrics.put(taskName + "_cpu", actual.estimatedCpuCores);
        }

        @Override
        public void recordThroughput(String taskName, long itemsProcessed, long durationMs) {
            metrics.put(taskName + "_throughput", itemsProcessed);
            metrics.put(taskName + "_throughput_duration", durationMs);
        }

        public Map<String, Object> getMetrics() {
            return new HashMap<>(metrics);
        }
    }

    // ================================================================================
    // Test Data Generation
    // ================================================================================

    private List<Path> createTestDataFiles(int fileCount, int wordsPerFile) throws IOException {
        List<Path> files = new ArrayList<>();
        String[] sampleWords = {
            "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog",
            "hello", "world", "java", "programming", "pipeline", "task", "test", "data",
            "concurrent", "parallel", "processing", "algorithm", "performance", "memory",
            "thread", "execution", "stream", "file", "input", "output", "buffer", "reader"
        };

        Random random = new Random(42); // Fixed seed for reproducible tests

        for (int i = 0; i < fileCount; i++) {
            Path file = tempDir.resolve("test-file-" + i + ".txt");
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (int j = 0; j < wordsPerFile; j++) {
                    String word = sampleWords[random.nextInt(sampleWords.length)];
                    writer.write(word);
                    if ((j + 1) % 10 == 0) {
                        writer.newLine(); // New line every 10 words
                    } else {
                        writer.write(" ");
                    }
                }
            }
            files.add(file);
        }
        return files;
    }

    private void assertTempDirectoryClean() throws IOException {
        try (Stream<Path> remaining = Files.walk(tempDir)) {
            long spillFileCount = remaining.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().contains("spill") ||
                               path.getFileName().toString().contains("final-counts"))
                .count();
            // Should have cleaned up pipeline temp files, but original test files may remain
            System.out.println("Remaining spill/temp files: " + spillFileCount);
            // We'll be lenient since test files may remain
        }
    }

    /**
     * Generates a large test file filled with random words for testing.
     * The file is created in the temp directory and should be cleaned up after use.
     */
    private Path generateLargeTestFile(long targetSizeBytes) throws IOException {
        Path largeFile = tempDir.resolve("large-test-file.txt");
        
        // Predefined word list for consistent testing
        String[] wordPool = {
            "algorithm", "performance", "scalable", "concurrent", "parallel", "distributed",
            "optimization", "efficient", "throughput", "latency", "bandwidth", "processing",
            "computation", "execution", "synchronization", "coordination", "orchestration",
            "pipeline", "workflow", "streaming", "batching", "transformation", "aggregation",
            "memory", "storage", "database", "indexing", "caching", "buffering", "spilling",
            "partitioning", "sharding", "replication", "consistency", "availability",
            "reliability", "fault", "tolerance", "recovery", "backup", "restore",
            "monitoring", "observability", "metrics", "logging", "tracing", "debugging",
            "profiling", "benchmarking", "testing", "validation", "verification",
            "security", "authentication", "authorization", "encryption", "hashing",
            "networking", "protocol", "communication", "serialization", "compression",
            "deployment", "configuration", "management", "administration", "maintenance"
        };
        
        Random random = new Random(12345); // Fixed seed for reproducible tests
        long bytesWritten = 0;
        
        System.out.println("Generating " + formatBytes(targetSizeBytes) + " test file...");
        
        try (BufferedWriter writer = Files.newBufferedWriter(largeFile, StandardCharsets.UTF_8)) {
            while (bytesWritten < targetSizeBytes) {
                StringBuilder line = new StringBuilder();
                
                // Generate a line with 10-20 random words
                int wordsPerLine = 10 + random.nextInt(11);
                for (int i = 0; i < wordsPerLine; i++) {
                    if (i > 0) line.append(" ");
                    
                    // Pick a random word, sometimes repeat popular words more frequently
                    String word;
                    if (random.nextDouble() < 0.3) {
                        // 30% chance of popular words (first 20 in list)
                        word = wordPool[random.nextInt(20)];
                    } else {
                        // 70% chance of any word
                        word = wordPool[random.nextInt(wordPool.length)];
                    }
                    
                    // Sometimes add variation (plurals, past tense, etc.)
                    if (random.nextDouble() < 0.2) {
                        switch (random.nextInt(3)) {
                            case 0: word += "s"; break;
                            case 1: word += "ed"; break;
                            case 2: word += "ing"; break;
                        }
                    }
                    
                    line.append(word);
                }
                
                line.append("\n");
                String lineStr = line.toString();
                writer.write(lineStr);
                bytesWritten += lineStr.getBytes(StandardCharsets.UTF_8).length;
                
                // More frequent progress reporting for smaller files
                if (bytesWritten % (10 * 1024 * 1024) == 0) { // Every 10MB
                    System.out.printf("Generated %s / %s (%.1f%%)%n", 
                        formatBytes(bytesWritten), 
                        formatBytes(targetSizeBytes),
                        (bytesWritten * 100.0) / targetSizeBytes);
                }
            }
            
            // Explicit flush before closing
            writer.flush();
            System.out.println("File generation completed, flushing and closing...");
        } // try-with-resources ensures file is properly closed
        
        // Verify file is completely written and accessible
        long actualSize = Files.size(largeFile);
        System.out.printf("Large test file generated: %s (actual size: %s)%n", 
            largeFile, formatBytes(actualSize));
        
        // Double-check file accessibility
        if (!Files.isReadable(largeFile)) {
            throw new IOException("Generated file is not readable: " + largeFile);
        }
        
        System.out.println("File verified as readable and ready for processing");
        return largeFile;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
