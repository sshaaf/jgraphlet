package dev.shaaf.jgraphlet.task;

import dev.shaaf.jgraphlet.pipeline.EnhancedTaskPipeline;
import dev.shaaf.jgraphlet.pipeline.PipelineContext;
import dev.shaaf.jgraphlet.pipeline.TaskPipeline;
import dev.shaaf.jgraphlet.pipeline.TaskPipelineConfig;
import dev.shaaf.jgraphlet.task.resource.ResourceAwareTask;
import dev.shaaf.jgraphlet.task.resource.ResourceConstraint;
import dev.shaaf.jgraphlet.task.resource.ResourceRequirements;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Demonstration of the enhanced JGraphlet capabilities including:
 * - Dynamic task creation
 * - Fan-out/fan-in patterns
 * - Resource management
 * - Streaming tasks
 * - Work stealing and load balancing
 * - Built-in task types
 */
public class EnhancementDemo {
    
    public static void main(String[] args) throws Exception {
        System.out.println("🚀 JGraphlet Enhancement Demo");
        System.out.println("==============================");
        
        // Demo 1: Basic Enhanced Pipeline with Resource Management
        demonstrateResourceManagement();
        
        // Demo 2: Dynamic Task Creation
        demonstrateDynamicTasks();
        
        // Demo 3: Fan-Out/Fan-In Pattern
        demonstrateFanOutFanIn();
        
        // Demo 4: Streaming Tasks
        demonstrateStreamingTasks();
        
        // Demo 5: Built-in Task Types
        demonstrateBuiltinTasks();
        
        System.out.println("\n✅ All demos completed successfully!");
    }
    
    /**
     * Demonstrates resource-aware task execution
     */
    private static void demonstrateResourceManagement() throws Exception {
        System.out.println("\n📊 Demo 1: Resource Management");
        System.out.println("-".repeat(40));
        
        // Create a simple resource manager
        TaskPipelineConfig.ResourceManager resourceManager = new SimpleResourceManager();
        
        TaskPipelineConfig config = TaskPipelineConfig.builder()
            .withResourceManager(resourceManager)
            .withMaxConcurrentTasks(4)
            .build();
        
        try (EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline(config)) {
            
            // Add a resource-aware task
            pipeline.add("resourceAwareTask", new MemoryIntensiveTask());
            
            List<String> input = Arrays.asList("data1", "data2", "data3");
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) pipeline.run(input).join();
            
            System.out.println("✅ Resource-aware processing completed: " + result.size() + " items");
        }
    }
    
    /**
     * Demonstrates dynamic task creation based on input
     */
    private static void demonstrateDynamicTasks() throws Exception {
        System.out.println("\n🔄 Demo 2: Dynamic-Style Processing");
        System.out.println("-".repeat(40));
        
        try (TaskPipeline pipeline = new TaskPipeline()) {
            
            // Add a task that simulates dynamic processing
            pipeline.add("dynamicSplitter", new DataSplitterTask());
            
            List<String> largeInput = generateData(1000);
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) pipeline.run(largeInput).join();
            
            System.out.println("✅ Dynamic-style processing completed: " + result.size() + " items processed in chunks");
        }
    }
    
    /**
     * Demonstrates fan-out/fan-in parallel processing
     */
    private static void demonstrateFanOutFanIn() throws Exception {
        System.out.println("\n🌟 Demo 3: Fan-Out/Fan-In Pattern");
        System.out.println("-".repeat(40));
        
        try (EnhancedTaskPipeline pipeline = new EnhancedTaskPipeline()) {
            
            List<String> datasets = Arrays.asList("dataset1", "dataset2", "dataset3", "dataset4");
            
            @SuppressWarnings("unchecked")
            List<Integer> result = (List<Integer>) pipeline
                .add("dataDiscovery", new DataDiscoveryTask())
                .fanOut("parallelProcessing")
                    .withTaskFactory(data -> {
                        // Create a task for each dataset
                        List<Task<?, ?>> tasks = new ArrayList<>();
                        @SuppressWarnings("unchecked")
                        List<String> dataList = (List<String>) data;
                        for (String dataset : dataList) {
                            tasks.add(new DataProcessingTask(dataset));
                        }
                        return tasks;
                    })
                    .withMaxParallelism(4)
                    .withLoadBalancing(true)
                .fanIn("aggregation", (Task<List<Object>, Object>) new ResultAggregatorTask())
                .run(datasets)
                .join();
            
            System.out.println("✅ Fan-out/fan-in processing completed with result: " + result);
        }
    }
    
    /**
     * Demonstrates streaming task capabilities
     */
    private static void demonstrateStreamingTasks() throws Exception {
        System.out.println("\n🌊 Demo 4: Streaming-Style Processing");
        System.out.println("-".repeat(40));
        
        try (TaskPipeline pipeline = new TaskPipeline()) {
            
            // Add task that demonstrates streaming-style processing internally
            pipeline.add("streamProcessor", new StreamingStyleTask());
            
            Integer range = 1000;
            @SuppressWarnings("unchecked")
            Long result = (Long) pipeline.run(range).join();
            
            System.out.println("✅ Streaming-style processing completed. Sum: " + result);
        }
    }
    
    /**
     * Demonstrates built-in task types
     */
    private static void demonstrateBuiltinTasks() throws Exception {
        System.out.println("\n🛠️ Demo 5: Built-in Task Types");
        System.out.println("-".repeat(40));
        
        try (TaskPipeline pipeline = new TaskPipeline()) {
            
            // Use built-in map, filter, and reduce tasks
            pipeline.add("mapper", new SquareMapTask())
                   .add("filter", new EvenFilterTask())
                   .add("reducer", new SumReduceTask());
            
            List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            @SuppressWarnings("unchecked")
            Integer result = (Integer) pipeline.run(numbers).join();
            
            System.out.println("✅ Built-in tasks completed. Sum of even squares: " + result);
        }
    }
    
    // Helper method to generate test data
    private static List<String> generateData(int size) {
        List<String> data = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            data.add("item_" + i);
        }
        return data;
    }
    
    // ========================================================================
    // Example Task Implementations
    // ========================================================================
    
    /**
     * Example resource-aware task
     */
    static class MemoryIntensiveTask implements ResourceAwareTask<List<String>, List<String>> {
        
        @Override
        public CompletableFuture<List<String>> execute(List<String> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                // Simulate memory-intensive processing
                List<String> result = new ArrayList<>();
                for (String item : input) {
                    result.add("processed_" + item);
                }
                return result;
            });
        }
        
        @Override
        public ResourceRequirements estimateResources(List<String> input) {
            long memoryBytes = input.size() * 1024L; // 1KB per item
            return new ResourceRequirements(memoryBytes, 0.5, false, Duration.ofSeconds(2));
        }
        
        @Override
        public void onResourceConstraint(ResourceConstraint constraint) {
            if (constraint.memoryConstrained) {
                System.out.println("⚠️ Memory constraint detected - reducing batch size");
            }
        }
    }
    
    /**
     * Example task that simulates dynamic processing by chunking data
     */
    static class DataSplitterTask implements Task<List<String>, List<String>> {
        
        @Override
        public CompletableFuture<List<String>> execute(List<String> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                // Simulate dynamic chunking by processing in parallel
                List<String> result = new ArrayList<>();
                
                // Split into chunks and process each
                int chunkSize = Math.max(1, input.size() / 4);
                List<CompletableFuture<List<String>>> chunkFutures = new ArrayList<>();
                
                for (int i = 0; i < input.size(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, input.size());
                    List<String> chunk = input.subList(i, end);
                    
                    chunkFutures.add(CompletableFuture.supplyAsync(() -> {
                        List<String> chunkResult = new ArrayList<>();
                        for (String item : chunk) {
                            chunkResult.add("dynamic_processed_" + item);
                        }
                        return chunkResult;
                    }));
                }
                
                // Wait for all chunks and combine results
                for (CompletableFuture<List<String>> future : chunkFutures) {
                    try {
                        result.addAll(future.get());
                    } catch (Exception e) {
                        throw new RuntimeException("Chunk processing failed", e);
                    }
                }
                
                return result;
            });
        }
    }
    
    /**
     * Processes a chunk of data
     */
    static class ChunkProcessorTask implements Task<Object, List<String>> {
        private final List<String> chunk;
        
        ChunkProcessorTask(List<String> chunk) {
            this.chunk = chunk;
        }
        
        @Override
        public CompletableFuture<List<String>> execute(Object input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                List<String> result = new ArrayList<>();
                for (String item : chunk) {
                    result.add("chunk_processed_" + item);
                }
                return result;
            });
        }
    }
    
    /**
     * Example task that demonstrates streaming-style processing internally
     */
    static class StreamingStyleTask implements Task<Integer, Long> {
        
        @Override
        public CompletableFuture<Long> execute(Integer range, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                // Demonstrate streaming-style processing with lazy evaluation
                return Stream.iterate(1, i -> i <= range, i -> i + 1)
                           .mapToLong(Integer::longValue)
                           .sum();
            });
        }
    }
    
    /**
     * Example map task that squares numbers
     */
    static class SquareMapTask extends MapTask<Integer, Integer> {
        @Override
        protected Integer map(Integer input) {
            return input * input;
        }
        
        @Override
        protected boolean supportsParallelExecution() {
            return true;
        }
    }
    
    /**
     * Example filter task that keeps even numbers
     */
    static class EvenFilterTask extends FilterTask<Integer> {
        @Override
        protected boolean test(Integer element) {
            return element % 2 == 0;
        }
        
        @Override
        protected boolean supportsParallelExecution() {
            return true;
        }
    }
    
    /**
     * Example reduce task that sums numbers
     */
    static class SumReduceTask extends ReduceTask<Integer, Integer> {
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
    
    // Additional supporting tasks for fan-out/fan-in demo
    
    static class DataDiscoveryTask implements Task<List<String>, List<String>> {
        @Override
        public CompletableFuture<List<String>> execute(List<String> input, PipelineContext context) {
            return CompletableFuture.completedFuture(input);
        }
    }
    
    static class DataProcessingTask implements Task<Object, Integer> {
        private final String dataset;
        
        DataProcessingTask(String dataset) {
            this.dataset = dataset;
        }
        
        @Override
        public CompletableFuture<Integer> execute(Object input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                // Simulate processing time
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return dataset.length(); // Return dataset name length as result
            });
        }
    }
    
    static class ResultAggregatorTask implements Task<List<Object>, Object> {
        @Override
        public CompletableFuture<Object> execute(List<Object> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                List<Integer> results = new ArrayList<>();
                for (Object obj : input) {
                    if (obj instanceof Integer) {
                        results.add((Integer) obj);
                    }
                }
                return results;
            });
        }
    }
    
    /**
     * Simple resource manager implementation
     */
    static class SimpleResourceManager implements TaskPipelineConfig.ResourceManager {
        private long availableMemory = 1024 * 1024 * 1024; // 1GB
        private double availableCpu = Runtime.getRuntime().availableProcessors();
        
        @Override
        public boolean canSchedule(ResourceRequirements requirements) {
            return requirements.estimatedMemoryBytes <= availableMemory &&
                   requirements.estimatedCpuCores <= availableCpu;
        }
        
        @Override
        public void reserveResources(ResourceRequirements requirements) {
            availableMemory -= requirements.estimatedMemoryBytes;
            availableCpu -= requirements.estimatedCpuCores;
        }
        
        @Override
        public void releaseResources(ResourceRequirements requirements) {
            availableMemory += requirements.estimatedMemoryBytes;
            availableCpu += requirements.estimatedCpuCores;
        }
        
        @Override
        public ResourceConstraint getCurrentConstraints() {
            boolean memoryConstrained = availableMemory < 100 * 1024 * 1024; // Less than 100MB
            boolean cpuConstrained = availableCpu < 0.5; // Less than 0.5 cores
            return new ResourceConstraint(memoryConstrained, cpuConstrained, false,
                                        availableMemory, availableCpu);
        }
    }
}
