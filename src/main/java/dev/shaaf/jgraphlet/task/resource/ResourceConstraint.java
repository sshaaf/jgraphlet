package dev.shaaf.jgraphlet.task.resource;

/**
 * Represents current resource constraints that may affect task execution.
 * Tasks implementing ResourceAwareTask can receive these constraints
 * and adapt their behavior accordingly.
 */
public class ResourceConstraint {
    
    /** Whether memory is currently constrained */
    public final boolean memoryConstrained;
    
    /** Whether CPU resources are constrained */
    public final boolean cpuConstrained;
    
    /** Whether I/O resources are constrained */
    public final boolean ioConstrained;
    
    /** Available memory in bytes (may be approximate) */
    public final long availableMemoryBytes;
    
    /** Available CPU cores (may be fractional) */
    public final double availableCpuCores;
    
    /**
     * Creates a new ResourceConstraint.
     * 
     * @param memoryConstrained Whether memory is constrained
     * @param cpuConstrained Whether CPU is constrained
     * @param ioConstrained Whether I/O is constrained
     * @param availableMemoryBytes Available memory in bytes
     * @param availableCpuCores Available CPU cores
     */
    public ResourceConstraint(boolean memoryConstrained, boolean cpuConstrained, boolean ioConstrained,
                             long availableMemoryBytes, double availableCpuCores) {
        this.memoryConstrained = memoryConstrained;
        this.cpuConstrained = cpuConstrained;
        this.ioConstrained = ioConstrained;
        this.availableMemoryBytes = availableMemoryBytes;
        this.availableCpuCores = availableCpuCores;
    }
    
    /**
     * Creates a ResourceConstraint with basic constraint flags.
     * 
     * @param memoryConstrained Whether memory is constrained
     * @param cpuConstrained Whether CPU is constrained
     * @param ioConstrained Whether I/O is constrained
     */
    public ResourceConstraint(boolean memoryConstrained, boolean cpuConstrained, boolean ioConstrained) {
        this(memoryConstrained, cpuConstrained, ioConstrained, -1, -1);
    }
    
    /**
     * Creates a ResourceConstraint indicating no constraints.
     * 
     * @return ResourceConstraint with no active constraints
     */
    public static ResourceConstraint none() {
        return new ResourceConstraint(false, false, false);
    }
    
    /**
     * Creates a ResourceConstraint indicating severe memory pressure.
     * 
     * @return ResourceConstraint for memory pressure scenario
     */
    public static ResourceConstraint memoryPressure() {
        return new ResourceConstraint(true, false, false);
    }
    
    /**
     * Creates a ResourceConstraint indicating CPU saturation.
     * 
     * @return ResourceConstraint for CPU saturation scenario
     */
    public static ResourceConstraint cpuSaturation() {
        return new ResourceConstraint(false, true, false);
    }
    
    /**
     * Creates a ResourceConstraint indicating I/O bottleneck.
     * 
     * @return ResourceConstraint for I/O bottleneck scenario
     */
    public static ResourceConstraint ioBottleneck() {
        return new ResourceConstraint(false, false, true);
    }
    
    /**
     * Checks if any resources are constrained.
     * 
     * @return true if any resource is constrained
     */
    public boolean hasConstraints() {
        return memoryConstrained || cpuConstrained || ioConstrained;
    }
    
    @Override
    public String toString() {
        return String.format("ResourceConstraint{memory=%s, cpu=%s, io=%s}",
                memoryConstrained, cpuConstrained, ioConstrained);
    }
}
