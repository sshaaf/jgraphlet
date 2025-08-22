package dev.shaaf.jgraphlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskPipelineTest {

    private ExecutorService executor;
    private TaskPipeline pipeline;

    // --- Test Setup ---

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        pipeline = new TaskPipeline(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // --- Stub Task Implementations for Testing ---

    @Test
    void shouldExecuteSimpleLinearChain() {
        // Arrange
        var taskA = new StringToLengthTask();
        var taskB = new SlowTask(10);
        pipeline.add("taskA", taskA)
                .then("taskB", taskB);

        // Act
        Object finalResult = pipeline.run("hello").join();

        // Assert
        assertNotNull(finalResult, "The final result should not be null.");
        // "hello" -> length 5 -> 5 * 2 = 10
        assertEquals(10, finalResult, "The final result of the chain is incorrect.");
    }

    @Test
    void shouldAddTaskSuccessfully() {
        // Arrange
        var taskA = new StringToLengthTask();

        // Act
        pipeline.add("taskA", taskA);

        // Assert
        assertNotNull(pipeline, "Pipeline should not be null after adding a task.");
    }

    @Test
    void shouldThrowExceptionWhenAddingDuplicateTask() {
        // Arrange
        var taskA = new StringToLengthTask();
        pipeline.add("taskA", taskA);

        // Act & Assert
        Exception exception = assertThrows(IllegalArgumentException.class, () -> pipeline.add("taskA", taskA));
        assertEquals("Task 'taskA' has already been added.", exception.getMessage());
    }

    @Test
    void shouldSetLastAddedTaskNameCorrectlyAfterAdd() {
        // Arrange
        String taskName = "taskA";
        var taskA = new StringToLengthTask();

        // Act
        pipeline.add(taskName, taskA);

        // Reflectively check lastAddedTaskName (not public API)
        try {
            var field = TaskPipeline.class.getDeclaredField("lastAddedTaskName");
            field.setAccessible(true);
            String lastAddedTaskName = (String) field.get(pipeline);

            // Assert
            assertEquals(taskName, lastAddedTaskName, "lastAddedTaskName should match the name of the last added task.");
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Reflection error occurred: " + e.getMessage());
        }
    }

    @Test
    void shouldRunIndependentTasksInParallel() {
        // Arrange: A -> B and A -> C, where B and C are slow.
        var taskA = new StringToLengthTask();
        var taskB = new SlowTask(500);
        var taskC = new LLMKindOfTask();
        pipeline.addTask("taskA", taskA).addTask("taskB", taskB).addTask("taskC", taskC);
        pipeline.connect("taskA", "taskB");
        pipeline.connect("taskA", "taskC");

        // Act
        long startTime = System.currentTimeMillis();
        // We can't get a final result because B and C are leaf nodes, so we create a dummy joiner task
        var joiner = new Task<Map<String, Object>, Integer>() {
            public CompletableFuture<Integer> execute(Map<String, Object> i, PipelineContext c) {
                return CompletableFuture.completedFuture(1);
            }
        };
        pipeline.addTask("joiner", joiner);
        pipeline.connect("taskB", "joiner");
        pipeline.connect("taskC", "joiner");

        pipeline.run("A long string").join();
        long duration = System.currentTimeMillis() - startTime;

        System.out.println("Parallel execution took: " + duration + "ms");

        // Assert
        // If B and C ran sequentially, it would take >1000ms.
        // If they run in parallel, it should take ~500ms + overhead.
        assertTrue(duration < 800, "Execution should be parallel and take less than 800ms.");
    }

    @Test
    void shouldHandleFanInWithMultipleInputs() {
        // Arrange: A -> C and B -> C
        var taskA = new StringToLengthTask();
        var taskB = new SlowTask(10);
        var taskC = new MultiInputTask();

        pipeline.addTask("taskA", taskA).addTask("taskB", taskB).addTask("taskC", taskC);
        pipeline.connect("taskA", "taskC");
        pipeline.connect("taskB", "taskC");

        // Act
        // This is tricky because taskA and taskB have different input types.
        // We'll use a wrapper to provide a common input type for the pipeline's start.
        var initialTask = new Task<String, Map<String, Object>>() {
            public CompletableFuture<Map<String, Object>> execute(String i, PipelineContext c) {
                return CompletableFuture.completedFuture(Map.of("string", "hello", "integer", 50));
            }
        };
        // This test setup is more complex and shows the need for careful pipeline design.
        // A simpler approach for this test would be to have tasks A and B take the same input type.
        // For now, let's assume a simplified graph where inputs match.
        // We'll test the gatherInputsFromCompletedParents logic.

        // Let's create a simpler graph for a clean test:
        pipeline = new TaskPipeline(executor); // Reset pipeline
        var startA = new Task<String, String>() {
            public CompletableFuture<String> execute(String i, PipelineContext c) {
                return CompletableFuture.completedFuture("hello");
            }
        };
        var startB = new Task<String, Integer>() {
            public CompletableFuture<Integer> execute(String i, PipelineContext c) {
                return CompletableFuture.completedFuture(100);
            }
        };
        var joinerTask = new Task<Map<String, Object>, String>() {
            public CompletableFuture<String> execute(Map<String, Object> i, PipelineContext c) {
                return CompletableFuture.completedFuture(i.get("startA") + ":" + i.get("startB"));
            }
        };

        pipeline.addTask("startA", startA).addTask("startB", startB).addTask("joinerTask", joinerTask);
        pipeline.connect("startA", "joinerTask");
        pipeline.connect("startB", "joinerTask");

        // Act
        Object finalResult = pipeline.run("dummy").join();

        // Assert
        assertEquals("hello:100", finalResult);
    }

    @Test
    void shouldCompleteExceptionallyWhenTaskFails() {
        // Arrange
        var taskA = new StringToLengthTask();
        var taskB = new FailingTask();
        var taskC = new SlowTask(10);
        pipeline.add("taskA", taskA).then("taskB", taskB).then("taskC", taskC);

        // Act
        CompletableFuture<Object> future = pipeline.run("test");

        // Assert
        // Use assertThrows to catch the expected top-level exception
        CompletionException thrown = assertThrows(
                CompletionException.class,
                future::join, // Method reference to the code that should throw
                "Expected pipeline.run().join() to throw CompletionException, but it didn't."
        );

        // Assert on the cause of the exception
        Throwable cause = thrown.getCause();
        assertNotNull(cause, "The CompletionException should have a cause.");
        assertInstanceOf(TaskRunException.class, cause, "The cause should be a TaskRunException.");
        assertEquals("This task was designed to fail.", cause.getMessage());
    }

    @Test
    void shouldRunSyncTaskChain() {
        // Arrange: upper -> suffix
        var upper = new UpperCaseSyncTask();
        var suffix = new SuffixSyncTask("!");
        pipeline.add("upper", upper).then("suffix", suffix);

        // Act
        Object result = pipeline.run("hello").join();

        // Assert
        assertEquals("HELLO!", result);
    }

    @Test
    void syncTaskExceptionShouldBeWrappedIntoTaskRunException() {
        // Arrange
        var explode = new ExplodingSyncTask();
        pipeline.add("explode", explode);

        // Act
        CompletableFuture<Object> f = pipeline.run("anything");

        // Assert
        CompletionException thrown = assertThrows(CompletionException.class, f::join);
        assertInstanceOf(TaskRunException.class, thrown.getCause());
    }

    // --- The Actual Test Cases ---

    @Test
    void shouldReturnInitialInputWhenNoTasks() {
        // Arrange: empty pipeline

        // Act
        Object result = pipeline.run("noop").join();

        // Assert
        assertEquals("noop", result);
    }

    @Test
    void shouldSupportFanOutAndJoinWithSyncTasks() {
        // Arrange: start -> double, start -> triple, then join sum
        var start = new UpperCaseSyncTask() {
            @Override
            public String executeSync(String input, PipelineContext context) {
                return "3";
            }
        };
        var doubleTask = new SyncTask<String, Integer>() {
            @Override
            public Integer executeSync(String input, PipelineContext context) {
                return Integer.parseInt(input) * 2;
            }
        };
        var tripleTask = new SyncTask<String, Integer>() {
            @Override
            public Integer executeSync(String input, PipelineContext context) {
                return Integer.parseInt(input) * 3;
            }
        };
        var joiner = new Task<Map<String, Object>, Integer>() {
            @Override
            public CompletableFuture<Integer> execute(Map<String, Object> input, PipelineContext context) {
                int a = (Integer) input.get("double");
                int b = (Integer) input.get("triple");
                return CompletableFuture.completedFuture(a + b);
            }
        };

        pipeline.addTask("start", start)
                .addTask("double", doubleTask)
                .addTask("triple", tripleTask)
                .addTask("join", joiner);

        pipeline.connect("start", "double");
        pipeline.connect("start", "triple");
        pipeline.connect("double", "join");
        pipeline.connect("triple", "join");

        // Act
        Object result = pipeline.run("ignored").join();

        // Assert: 3*2 + 3*3 = 6 + 9 = 15
        assertEquals(15, result);
    }

    @Test
    void shouldCacheResultsForCacheableTask() {
        // Arrange
        var cacheable = new CacheableSyncTask();
        pipeline.add("cacheable", cacheable);

        // Act
        Object first = pipeline.run("same-input").join();
        Object second = pipeline.run("same-input").join();

        // Assert
        assertEquals(1, first);
        assertEquals(1, second);
        assertEquals(1, cacheable.callCount(), "Cacheable task should be called only once for the same input.");
    }

    @Test
    void cacheShouldBePerInput() {
        // Arrange
        var cacheable = new CacheableSyncTask();
        pipeline.add("cacheable", cacheable);

        // Act
        Object a = pipeline.run("A").join();
        Object b = pipeline.run("B").join();
        Object a2 = pipeline.run("A").join();

        // Assert
        assertEquals(1, a);
        assertEquals(2, b);
        assertEquals(1, a2);
        assertEquals(2, cacheable.callCount(), "Two distinct inputs should produce two executions.");
    }

    @Test
    void connectShouldRejectNullNames() {
        // Arrange
        pipeline.addTask("x", new StringToLengthTask());

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> pipeline.connect(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> pipeline.connect("x", null));
    }

    @Test
    void connectShouldRejectSelfConnection() {
        // Arrange
        pipeline.addTask("x", new StringToLengthTask());

        // Act & Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> pipeline.connect("x", "x"));
        assertTrue(ex.getMessage().contains("Cannot connect a task to itself"));
    }

    @Test
    void connectShouldRequireExistingTasks() {
        // Arrange
        pipeline.addTask("a", new StringToLengthTask());

        // from not added
        IllegalStateException ex1 = assertThrows(IllegalStateException.class, () -> pipeline.connect("missing", "a"));
        assertTrue(ex1.getMessage().contains("must be added"));

        // to not added
        pipeline.addTask("b", new StringToLengthTask());
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, () -> pipeline.connect("a", "missing"));
        assertTrue(ex2.getMessage().contains("must be added"));
    }

    @Test
    void examplesTest() {
// Create tasks that run in parallel
        SyncTask<String, String> fetchUserTask = (userId, context) ->
                "User: " + userId;

        SyncTask<String, String> fetchPrefsTask = (userId, context) ->
                "Prefs: dark_mode=true";

// Fan-in task that receives results from multiple parents
        Task<Map<String, Object>, String> combineTask = (inputs, context) ->
                CompletableFuture.supplyAsync(() -> {
                    String user = (String) inputs.get("fetchUser");
                    String prefs = (String) inputs.get("fetchPrefs");
                    return user + " | " + prefs;
                });

// Build the fan-in pipeline
        TaskPipeline pipeline = new TaskPipeline()
                .addTask("fetchUser", fetchUserTask)
                .addTask("fetchPrefs", fetchPrefsTask)
                .addTask("combine", combineTask);

// Connect the fan-in
        pipeline.connect("fetchUser", "combine")
                .connect("fetchPrefs", "combine");

// Execute - both fetch tasks run in parallel!
        String result = (String) pipeline.run("user123").join();
    }

    static class StringToLengthTask implements Task<String, Integer> {
        @Override
        public CompletableFuture<Integer> execute(String input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> input.length());
        }
    }

    static class LLMKindOfTask implements Task<Integer, Integer> {
        private final int durationMs = 600;

        @Override
        public CompletableFuture<Integer> execute(Integer input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(durationMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return input * 2;
            });
        }
    }

    static class SlowTask implements Task<Integer, Integer> {
        private final int durationMs;

        public SlowTask(int durationMs) {
            this.durationMs = durationMs;
        }

        @Override
        public CompletableFuture<Integer> execute(Integer input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(durationMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return input * 2;
            });
        }
    }

    static class MultiInputTask implements Task<Map<String, Object>, String> {
        @Override
        public CompletableFuture<String> execute(Map<String, Object> input, PipelineContext context) {
            return CompletableFuture.supplyAsync(() -> {
                // Combines results from two known parent tasks
                Integer length = (Integer) input.get("StringToLengthTask");
                Integer doubled = (Integer) input.get("SlowTask");
                return length + ":" + doubled;
            });
        }
    }

    static class FailingTask implements Task<Object, Object> {
        @Override
        public CompletableFuture<Object> execute(Object input, PipelineContext context) {
            return CompletableFuture.failedFuture(new TaskRunException("This task was designed to fail."));
        }
    }

    // SyncTask-based helpers
    static class UpperCaseSyncTask implements SyncTask<String, String> {
        @Override
        public String executeSync(String input, PipelineContext context) {
            return input.toUpperCase();
        }
    }

    static class SuffixSyncTask implements SyncTask<String, String> {
        private final String suffix;

        SuffixSyncTask(String suffix) {
            this.suffix = suffix;
        }

        @Override
        public String executeSync(String input, PipelineContext context) {
            return input + suffix;
        }
    }

    static class ExplodingSyncTask implements SyncTask<String, String> {
        @Override
        public String executeSync(String input, PipelineContext context) {
            throw new IllegalStateException("boom");
        }
    }

    static class CacheableSyncTask implements SyncTask<String, Integer> {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Integer executeSync(String input, PipelineContext context) {
            return calls.incrementAndGet();
        }

        @Override
        public boolean isCacheable() {
            return true;
        }

        int callCount() {
            return calls.get();
        }
    }

}