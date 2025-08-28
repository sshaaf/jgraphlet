package dev.shaaf.jgraphlet.task.resource;

import dev.shaaf.jgraphlet.pipeline.PipelineContext;
import dev.shaaf.jgraphlet.pipeline.TaskPipelineConfig;
import dev.shaaf.jgraphlet.task.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for resource management functionality.
 */
class ResourceManagementTest {

    private TestResourceManager resourceManager;

    @BeforeEach
    void setUp() {
        resourceManager = new TestResourceManager(1024 * 1024); // 1MB available
    }

    // ========================================================================
    // ResourceRequirements Tests
    // ========================================================================

    @Test
    @DisplayName("Resource requirements should be created with correct values")
    void testResourceRequirementsCreation() {
        ResourceRequirements req = new ResourceRequirements(1024, 0.5, true, Duration.ofSeconds(30));
        
        assertEquals(1024, req.estimatedMemoryBytes);
        assertEquals(0.5, req.estimatedCpuCores);
        assertTrue(req.isIOIntensive);
        assertEquals(Duration.ofSeconds(30), req.estimatedDuration);
    }

    @Test
    @DisplayName("Resource requirements factory methods should work correctly")
    void testResourceRequirementsFactoryMethods() {
        // Test minimal requirements
        ResourceRequirements minimal = ResourceRequirements.minimal();
        assertEquals(1024 * 1024, minimal.estimatedMemoryBytes);
        assertEquals(0.1, minimal.estimatedCpuCores);
        assertFalse(minimal.isIOIntensive);
        assertEquals(Duration.ofSeconds(1), minimal.estimatedDuration);
        
        // Test CPU intensive
        ResourceRequirements cpuIntensive = ResourceRequirements.cpuIntensive(2048);
        assertEquals(2048, cpuIntensive.estimatedMemoryBytes);
        assertEquals(1.0, cpuIntensive.estimatedCpuCores);
        assertFalse(cpuIntensive.isIOIntensive);
        assertEquals(Duration.ofMinutes(5), cpuIntensive.estimatedDuration);
        
        // Test I/O intensive
        ResourceRequirements ioIntensive = ResourceRequirements.ioIntensive(512);
        assertEquals(512, ioIntensive.estimatedMemoryBytes);
        assertEquals(0.2, ioIntensive.estimatedCpuCores);
        assertTrue(ioIntensive.isIOIntensive);
        assertEquals(Duration.ofMinutes(2), ioIntensive.estimatedDuration);
    }

    // ========================================================================
    // ResourceConstraint Tests
    // ========================================================================

    @Test
    @DisplayName("Resource constraints should be created and queried correctly")
    void testResourceConstraintCreation() {
        ResourceConstraint constraint = new ResourceConstraint(true, false, true, 512, 2.0);
        
        assertTrue(constraint.memoryConstrained);
        assertFalse(constraint.cpuConstrained);
        assertTrue(constraint.ioConstrained);
        assertEquals(512, constraint.availableMemoryBytes);
        assertEquals(2.0, constraint.availableCpuCores);
        
        assertTrue(constraint.hasConstraints());
    }

    @Test
    @DisplayName("Resource constraint factory methods should work correctly")
    void testResourceConstraintFactoryMethods() {
        // Test no constraints
        ResourceConstraint none = ResourceConstraint.none();
        assertFalse(none.hasConstraints());
        
        // Test memory pressure
        ResourceConstraint memPressure = ResourceConstraint.memoryPressure();
        assertTrue(memPressure.memoryConstrained);
        assertFalse(memPressure.cpuConstrained);
        assertFalse(memPressure.ioConstrained);
        
        // Test CPU saturation
        ResourceConstraint cpuSat = ResourceConstraint.cpuSaturation();
        assertFalse(cpuSat.memoryConstrained);
        assertTrue(cpuSat.cpuConstrained);
        assertFalse(cpuSat.ioConstrained);
        
        // Test I/O bottleneck
        ResourceConstraint ioBottleneck = ResourceConstraint.ioBottleneck();
        assertFalse(ioBottleneck.memoryConstrained);
        assertFalse(ioBottleneck.cpuConstrained);
        assertTrue(ioBottleneck.ioConstrained);
    }

    // ========================================================================
    // ResourceAwareTask Tests
    // ========================================================================

    @Test
    @DisplayName("Resource-aware task should estimate resources correctly")
    void testResourceAwareTaskEstimation() {
        TestResourceAwareTask task = new TestResourceAwareTask(512, 0.25, true);
        
        List<String> input = Arrays.asList("data1", "data2", "data3");
        ResourceRequirements requirements = task.estimateResources(input);
        
        assertEquals(512, requirements.estimatedMemoryBytes);
        assertEquals(0.25, requirements.estimatedCpuCores);
        assertTrue(requirements.isIOIntensive);
    }

    @Test
    @DisplayName("Resource-aware task should handle constraints")
    void testResourceAwareTaskConstraintHandling() {
        TestResourceAwareTask task = new TestResourceAwareTask(512, 0.25, false);
        
        // Test constraint notification
        ResourceConstraint constraint = ResourceConstraint.memoryPressure();
        task.onResourceConstraint(constraint);
        
        assertTrue(task.wasConstraintNotified());
    }

    @Test
    @DisplayName("Resource-aware task should provide minimum requirements")
    void testResourceAwareTaskMinimumRequirements() {
        TestResourceAwareTask task = new TestResourceAwareTask(1024, 0.5, false);
        
        List<String> input = Arrays.asList("test");
        ResourceRequirements minReq = task.getMinimumResources(input);
        
        // Should be minimal by default
        assertEquals(1024 * 1024, minReq.estimatedMemoryBytes);
        assertEquals(0.1, minReq.estimatedCpuCores);
    }

    @Test
    @DisplayName("Resource-aware task should indicate deferrability")
    void testResourceAwareTaskDeferrability() {
        TestResourceAwareTask deferrable = new TestResourceAwareTask(512, 0.1, false);
        assertTrue(deferrable.isDeferrable());
        
        TestNonDeferrableTask nonDeferrable = new TestNonDeferrableTask();
        assertFalse(nonDeferrable.isDeferrable());
    }

    @Test
    @DisplayName("Resource-aware task should report actual usage")
    void testResourceAwareTaskUsageReporting() {
        TestResourceAwareTask task = new TestResourceAwareTask(512, 0.25, false);
        
        ResourceRequirements actualUsage = new ResourceRequirements(400, 0.2, false, Duration.ofSeconds(5));
        task.reportActualUsage(actualUsage);
        
        assertTrue(task.wasUsageReported());
    }

    // ========================================================================
    // Resource Manager Tests
    // ========================================================================

    @Test
    @DisplayName("Resource manager should handle basic operations")
    void testResourceManagerBasicOperations() {
        ResourceRequirements req = new ResourceRequirements(512, 0.5, false);
        
        // Initial state
        assertTrue(resourceManager.canSchedule(req));
        assertEquals(0, resourceManager.getCurrentUsage());
        
        // Reserve resources
        resourceManager.reserveResources(req);
        assertEquals(512, resourceManager.getCurrentUsage());
        
        // Should still be able to schedule more
        assertTrue(resourceManager.canSchedule(req));
        
        // Release resources
        resourceManager.releaseResources(req);
        assertEquals(0, resourceManager.getCurrentUsage());
    }

    @Test
    @DisplayName("Resource manager should enforce limits")
    void testResourceManagerLimits() {
        ResourceRequirements largeReq = new ResourceRequirements(2 * 1024 * 1024, 1.0, false); // 2MB
        
        assertFalse(resourceManager.canSchedule(largeReq));
    }

    @Test
    @DisplayName("Resource manager atomic operations should work correctly")
    void testResourceManagerAtomicOperations() {
        ResourceRequirements req = new ResourceRequirements(512, 0.5, false);
        
        // Test successful atomic reservation
        assertTrue(resourceManager.tryReserveResources(req));
        assertEquals(512, resourceManager.getCurrentUsage());
        
        // Test failed atomic reservation
        ResourceRequirements largeReq = new ResourceRequirements(2 * 1024 * 1024, 1.0, false);
        assertFalse(resourceManager.tryReserveResources(largeReq));
        assertEquals(512, resourceManager.getCurrentUsage()); // Should be unchanged
        
        // Test safe release
        assertTrue(resourceManager.safeReleaseResources(req));
        assertEquals(0, resourceManager.getCurrentUsage());
        
        // Test double release (should be safe)
        assertFalse(resourceManager.safeReleaseResources(req));
        assertEquals(0, resourceManager.getCurrentUsage());
    }

    @Test
    @DisplayName("Resource manager should provide constraint information")
    void testResourceManagerConstraints() {
        // Initially no constraints
        ResourceConstraint constraints = resourceManager.getCurrentConstraints();
        assertFalse(constraints.memoryConstrained);
        
        // Reserve most memory
        ResourceRequirements largeReq = new ResourceRequirements(900 * 1024, 0.1, false); // 900KB
        resourceManager.reserveResources(largeReq);
        
        // Should now be constrained
        constraints = resourceManager.getCurrentConstraints();
        assertTrue(constraints.memoryConstrained);
    }

    // ========================================================================
    // Integration Tests
    // ========================================================================

    @Test
    @DisplayName("Resource-aware task execution should work end-to-end")
    void testResourceAwareTaskExecution() throws Exception {
        TestResourceAwareTask task = new TestResourceAwareTask(256, 0.1, false);
        PipelineContext context = new PipelineContext();
        
        List<String> input = Arrays.asList("test1", "test2");
        
        // Execute task
        CompletableFuture<List<String>> future = task.execute(input, context);
        List<String> result = future.join();
        
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("processed_test1", result.get(0));
        assertEquals("processed_test2", result.get(1));
    }

    // ========================================================================
    // Test Implementation Classes
    // ========================================================================

    static class TestResourceAwareTask implements ResourceAwareTask<List<String>, List<String>> {
        private final long memoryRequired;
        private final double cpuRequired;
        private final boolean ioIntensive;
        private final AtomicBoolean constraintNotified = new AtomicBoolean(false);
        private final AtomicBoolean usageReported = new AtomicBoolean(false);

        TestResourceAwareTask(long memoryRequired, double cpuRequired, boolean ioIntensive) {
            this.memoryRequired = memoryRequired;
            this.cpuRequired = cpuRequired;
            this.ioIntensive = ioIntensive;
        }

        @Override
        public CompletableFuture<List<String>> execute(List<String> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                return input.stream()
                    .map(item -> "processed_" + item)
                    .toList();
            });
        }

        @Override
        public ResourceRequirements estimateResources(List<String> input) {
            return new ResourceRequirements(memoryRequired, cpuRequired, ioIntensive, Duration.ofSeconds(1));
        }

        @Override
        public void onResourceConstraint(ResourceConstraint constraint) {
            constraintNotified.set(true);
        }

        @Override
        public void reportActualUsage(ResourceRequirements actualUsage) {
            usageReported.set(true);
        }

        public boolean wasConstraintNotified() {
            return constraintNotified.get();
        }

        public boolean wasUsageReported() {
            return usageReported.get();
        }
    }

    static class TestNonDeferrableTask implements ResourceAwareTask<String, String> {
        @Override
        public CompletableFuture<String> execute(String input, PipelineContext context) {
            return CompletableFuture.completedFuture("processed_" + input);
        }

        @Override
        public ResourceRequirements estimateResources(String input) {
            return ResourceRequirements.minimal();
        }

        @Override
        public void onResourceConstraint(ResourceConstraint constraint) {
            // Handle constraint
        }

        @Override
        public boolean isDeferrable() {
            return false; // Time-critical task
        }
    }

    static class TestResourceManager implements TaskPipelineConfig.ResourceManager {
        private final AtomicLong availableMemory;
        private final AtomicLong usedMemory = new AtomicLong(0);

        TestResourceManager(long totalMemory) {
            this.availableMemory = new AtomicLong(totalMemory);
        }

        @Override
        public synchronized boolean canSchedule(ResourceRequirements requirements) {
            return usedMemory.get() + requirements.estimatedMemoryBytes <= availableMemory.get();
        }

        @Override
        public synchronized void reserveResources(ResourceRequirements requirements) {
            if (canSchedule(requirements)) {
                usedMemory.addAndGet(requirements.estimatedMemoryBytes);
            } else {
                throw new IllegalStateException("Cannot reserve resources");
            }
        }

        @Override
        public synchronized void releaseResources(ResourceRequirements requirements) {
            usedMemory.addAndGet(-requirements.estimatedMemoryBytes);
        }

        @Override
        public synchronized boolean tryReserveResources(ResourceRequirements requirements) {
            if (canSchedule(requirements)) {
                usedMemory.addAndGet(requirements.estimatedMemoryBytes);
                return true;
            }
            return false;
        }

        @Override
        public synchronized boolean safeReleaseResources(ResourceRequirements requirements) {
            if (usedMemory.get() >= requirements.estimatedMemoryBytes) {
                usedMemory.addAndGet(-requirements.estimatedMemoryBytes);
                return true;
            }
            return false;
        }

        @Override
        public ResourceConstraint getCurrentConstraints() {
            boolean memoryConstrained = usedMemory.get() > availableMemory.get() * 0.8;
            return new ResourceConstraint(memoryConstrained, false, false,
                                        availableMemory.get() - usedMemory.get(), 4.0);
        }

        public long getCurrentUsage() {
            return usedMemory.get();
        }
    }
}
