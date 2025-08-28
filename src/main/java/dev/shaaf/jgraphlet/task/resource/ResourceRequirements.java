package dev.shaaf.jgraphlet.task.resource;

import java.time.Duration;

/**
 * Represents the estimated resource requirements for a task execution.
 * This information is used by the pipeline's resource manager to make
 * scheduling decisions and prevent resource starvation.
 */
public class ResourceRequirements {
    
    /** Estimated memory usage in bytes */
    public final long estimatedMemoryBytes;
    
    /** Estimated CPU cores needed (can be fractional) */
    public final double estimatedCpuCores;
    
    /** Whether this task is I/O intensive */
    public final boolean isIOIntensive;
    
    /** Estimated execution duration */
    public final Duration estimatedDuration;
    
    /**
     * Creates a new ResourceRequirements instance.
     * 
     * @param memoryBytes Estimated memory usage in bytes
     * @param cpuCores Estimated CPU cores needed
     * @param ioIntensive Whether the task is I/O intensive
     * @param duration Estimated execution duration
     */
    public ResourceRequirements(long memoryBytes, double cpuCores, boolean ioIntensive, Duration duration) {
        this.estimatedMemoryBytes = memoryBytes;
        this.estimatedCpuCores = cpuCores;
        this.isIOIntensive = ioIntensive;
        this.estimatedDuration = duration;
    }
    
    /**
     * Creates a ResourceRequirements with default duration.
     * 
     * @param memoryBytes Estimated memory usage in bytes
     * @param cpuCores Estimated CPU cores needed
     * @param ioIntensive Whether the task is I/O intensive
     */
    public ResourceRequirements(long memoryBytes, double cpuCores, boolean ioIntensive) {
        this(memoryBytes, cpuCores, ioIntensive, Duration.ofSeconds(30));
    }
    
    /**
     * Creates a minimal ResourceRequirements for lightweight tasks.
     * 
     * @return ResourceRequirements for a lightweight task
     */
    public static ResourceRequirements minimal() {
        return new ResourceRequirements(1024 * 1024, 0.1, false, Duration.ofSeconds(1));
    }
    
    /**
     * Creates ResourceRequirements for a CPU-intensive task.
     * 
     * @param memoryBytes Memory requirement in bytes
     * @return ResourceRequirements for CPU-intensive processing
     */
    public static ResourceRequirements cpuIntensive(long memoryBytes) {
        return new ResourceRequirements(memoryBytes, 1.0, false, Duration.ofMinutes(5));
    }
    
    /**
     * Creates ResourceRequirements for an I/O-intensive task.
     * 
     * @param memoryBytes Memory requirement in bytes
     * @return ResourceRequirements for I/O-intensive processing
     */
    public static ResourceRequirements ioIntensive(long memoryBytes) {
        return new ResourceRequirements(memoryBytes, 0.2, true, Duration.ofMinutes(2));
    }
    
    @Override
    public String toString() {
        return String.format("ResourceRequirements{memory=%d bytes, cpu=%.2f cores, io=%s, duration=%s}",
                estimatedMemoryBytes, estimatedCpuCores, isIOIntensive, estimatedDuration);
    }
}
