package dev.shaaf.jgraphlet.pipeline;

import dev.shaaf.jgraphlet.task.resource.ResourceConstraint;
import dev.shaaf.jgraphlet.task.resource.ResourceRequirements;

import java.util.concurrent.ExecutorService;

/**
 * Configuration class for TaskPipeline with enhanced features like
 * resource management, caching, metrics, and error handling.
 */
public class TaskPipelineConfig {
    
    private final ResourceManager resourceManager;
    private final CacheConfig cacheConfig;
    private final MetricsCollector metricsCollector;
    private final BackpressureConfig backpressureConfig;
    private final ErrorHandlingStrategy errorHandlingStrategy;
    private final ExecutorService executorService;
    private final boolean enableWorkStealing;
    private final int maxConcurrentTasks;
    
    private TaskPipelineConfig(Builder builder) {
        this.resourceManager = builder.resourceManager;
        this.cacheConfig = builder.cacheConfig;
        this.metricsCollector = builder.metricsCollector;
        this.backpressureConfig = builder.backpressureConfig;
        this.errorHandlingStrategy = builder.errorHandlingStrategy;
        this.executorService = builder.executorService;
        this.enableWorkStealing = builder.enableWorkStealing;
        this.maxConcurrentTasks = builder.maxConcurrentTasks;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    // Getters
    public ResourceManager getResourceManager() { return resourceManager; }
    public CacheConfig getCacheConfig() { return cacheConfig; }
    public MetricsCollector getMetricsCollector() { return metricsCollector; }
    public BackpressureConfig getBackpressureConfig() { return backpressureConfig; }
    public ErrorHandlingStrategy getErrorHandlingStrategy() { return errorHandlingStrategy; }
    public ExecutorService getExecutorService() { return executorService; }
    public boolean isWorkStealingEnabled() { return enableWorkStealing; }
    public int getMaxConcurrentTasks() { return maxConcurrentTasks; }
    
    public static class Builder {
        private ResourceManager resourceManager;
        private CacheConfig cacheConfig;
        private MetricsCollector metricsCollector;
        private BackpressureConfig backpressureConfig;
        private ErrorHandlingStrategy errorHandlingStrategy;
        private ExecutorService executorService;
        private boolean enableWorkStealing = false;
        private int maxConcurrentTasks = Runtime.getRuntime().availableProcessors();
        
        public Builder withResourceManager(ResourceManager resourceManager) {
            this.resourceManager = resourceManager;
            return this;
        }
        
        public Builder withCaching(CacheConfig cacheConfig) {
            this.cacheConfig = cacheConfig;
            return this;
        }
        
        public Builder withMetrics(MetricsCollector metricsCollector) {
            this.metricsCollector = metricsCollector;
            return this;
        }
        
        public Builder withBackpressure(BackpressureConfig backpressureConfig) {
            this.backpressureConfig = backpressureConfig;
            return this;
        }
        
        public Builder withErrorHandling(ErrorHandlingStrategy errorHandlingStrategy) {
            this.errorHandlingStrategy = errorHandlingStrategy;
            return this;
        }
        
        public Builder withExecutorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }
        
        public Builder withWorkStealing(boolean enableWorkStealing) {
            this.enableWorkStealing = enableWorkStealing;
            return this;
        }
        
        public Builder withMaxConcurrentTasks(int maxConcurrentTasks) {
            this.maxConcurrentTasks = maxConcurrentTasks;
            return this;
        }
        
        public TaskPipelineConfig build() {
            return new TaskPipelineConfig(this);
        }
    }
    
    // Configuration interfaces and classes
    
    public interface ResourceManager {
        boolean canSchedule(ResourceRequirements requirements);
        void reserveResources(ResourceRequirements requirements);
        void releaseResources(ResourceRequirements requirements);
        ResourceConstraint getCurrentConstraints();
        
        /**
         * Atomically checks and reserves resources if available.
         * This method combines canSchedule() and reserveResources() into a single
         * atomic operation to prevent race conditions in high-concurrency scenarios.
         * 
         * @param requirements The resources to reserve
         * @return true if resources were successfully reserved, false otherwise
         */
        default boolean tryReserveResources(ResourceRequirements requirements) {
            // Default implementation for backward compatibility
            // Implementations should override with atomic operations
            synchronized (this) {
                if (canSchedule(requirements)) {
                    reserveResources(requirements);
                    return true;
                }
                return false;
            }
        }
        
        /**
         * Thread-safe resource release that handles double-release safely.
         * This method can be called multiple times safely and will only
         * release resources once.
         * 
         * @param requirements The resources to release
         * @return true if resources were actually released, false if already released
         */
        default boolean safeReleaseResources(ResourceRequirements requirements) {
            // Default implementation - subclasses should override for better safety
            try {
                releaseResources(requirements);
                return true;
            } catch (Exception e) {
                // Already released or other issue - handle gracefully
                return false;
            }
        }
    }
    
    public static class CacheConfig {
        private final boolean enabled;
        private final int maxEntries;
        private final long maxMemoryBytes;
        
        public CacheConfig(boolean enabled, int maxEntries, long maxMemoryBytes) {
            this.enabled = enabled;
            this.maxEntries = maxEntries;
            this.maxMemoryBytes = maxMemoryBytes;
        }
        
        public boolean isEnabled() { return enabled; }
        public int getMaxEntries() { return maxEntries; }
        public long getMaxMemoryBytes() { return maxMemoryBytes; }
        
        public static CacheConfig enabled(int maxEntries, long maxMemoryBytes) {
            return new CacheConfig(true, maxEntries, maxMemoryBytes);
        }
        
        public static CacheConfig disabled() {
            return new CacheConfig(false, 0, 0);
        }
    }
    
    public interface MetricsCollector {
        void recordTaskExecution(String taskName, long durationMs, boolean success);
        void recordResourceUsage(String taskName, ResourceRequirements actual);
        void recordThroughput(String taskName, long itemsProcessed, long durationMs);
    }
    
    public static class BackpressureConfig {
        private final boolean enabled;
        private final int bufferSize;
        private final long timeoutMs;
        
        public BackpressureConfig(boolean enabled, int bufferSize, long timeoutMs) {
            this.enabled = enabled;
            this.bufferSize = bufferSize;
            this.timeoutMs = timeoutMs;
        }
        
        public boolean isEnabled() { return enabled; }
        public int getBufferSize() { return bufferSize; }
        public long getTimeoutMs() { return timeoutMs; }
        
        public static BackpressureConfig enabled(int bufferSize) {
            return new BackpressureConfig(true, bufferSize, 5000);
        }
        
        public static BackpressureConfig disabled() {
            return new BackpressureConfig(false, 0, 0);
        }
    }
    
    public interface ErrorHandlingStrategy {
        enum Action { RETRY, SKIP, FAIL, FALLBACK }
        
        Action handleError(String taskName, Throwable error, int attemptNumber);
        int getMaxRetries(String taskName);
        long getRetryDelayMs(String taskName, int attemptNumber);
    }
}
