package dev.shaaf.jgraphlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class TaskPipelineContextIntegrationTest {

    private ExecutorService executor;
    private TaskPipeline pipeline;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        pipeline = new TaskPipeline(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void shouldPropagateDataViaContextBetweenTasks() {
        // Task A: writes into context and passes through the input
        Task<String, String> producer = (input, ctx) -> CompletableFuture.supplyAsync(() -> {
            ctx.put("greeting", "hello");
            return input;
        });

        // Task B: reads from context and combines with input
        Task<String, String> consumer = (input, ctx) -> CompletableFuture.supplyAsync(() -> {
            String suffix = ctx.get("greeting", String.class).orElse("missing");
            return input + "-" + suffix;
        });

        pipeline.add("producer", producer).then("consumer", consumer);

        Object result = pipeline.run("world").join();
        assertEquals("world-hello", result);
    }

    @Test
    void contextShouldBeIsolatedPerRun() {
        // Task writes a counter only if absent, then returns it
        Task<String, Integer> counterWriter = (input, ctx) -> CompletableFuture.supplyAsync(() -> {
            Integer current = ctx.get("counter", Integer.class).orElse(0);
            ctx.put("counter", current + 1);
            return ctx.get("counter", Integer.class).orElseThrow();
        });

        pipeline.add("counterWriter", counterWriter);

        Object first = pipeline.run("x").join();
        Object second = pipeline.run("y").join();

        // Each run starts with a fresh context, so both should be 1
        assertEquals(1, first);
        assertEquals(1, second);
    }

    @Test
    void syncTasksShouldUseContextForDataExchange() {
        // Sync producer puts a key and returns fixed output
        SyncTask<String, String> syncProducer = (input, ctx) -> {
            ctx.put("userId", 101);
            return "ok";
        };

        // Sync consumer reads that key and returns derived value
        SyncTask<String, String> syncConsumer = (input, ctx) -> {
            int id = ctx.get("userId", Integer.class).orElseThrow();
            return "user-" + id;
        };

        pipeline.add("syncProducer", syncProducer).then("syncConsumer", syncConsumer);

        Object result = pipeline.run("ignored").join();
        assertEquals("user-101", result);
    }

    @Test
    void shouldHandleMissingContextKeyGracefullyInTask() {
        Task<String, String> reader = (input, ctx) -> CompletableFuture.supplyAsync(() -> {
            Optional<String> value = ctx.get("missingKey", String.class);
            return value.orElse("default");
        });

        pipeline.add("reader", reader);

        Object result = pipeline.run("irrelevant").join();
        assertEquals("default", result);
    }

    @Test
    void fanOutBranchesShouldWriteDifferentKeysAndJoinerReadsBoth() {
        // Start task returns an input token
        Task<String, String> start = (input, ctx) -> CompletableFuture.completedFuture("seed");

        // Branch A writes keyA
        Task<String, String> branchA = (input, ctx) -> CompletableFuture.supplyAsync(() -> {
            ctx.put("keyA", "A1");
            return "doneA";
        });

        // Branch B writes keyB
        Task<String, String> branchB = (input, ctx) -> CompletableFuture.supplyAsync(() -> {
            ctx.put("keyB", "B2");
            return "doneB";
        });

        // Joiner reads both keys from context (not from fan-in map) and combines
        Task<Map<String, Object>, String> joiner = (fanIn, ctx) -> CompletableFuture.supplyAsync(() -> {
            String a = ctx.get("keyA", String.class).orElse("na");
            String b = ctx.get("keyB", String.class).orElse("nb");
            return a + "+" + b;
        });

        pipeline.addTask("start", start)
                .addTask("branchA", branchA)
                .addTask("branchB", branchB)
                .addTask("joiner", joiner);

        pipeline.connect("start", "branchA");
        pipeline.connect("start", "branchB");
        pipeline.connect("branchA", "joiner");
        pipeline.connect("branchB", "joiner");

        Object result = pipeline.run("ignored").join();
        assertEquals("A1+B2", result);
    }

    @Test
    void laterTaskCanOverwriteContextValue() {
        // Writer1 sets a value
        Task<String, String> writer1 = (in, ctx) -> CompletableFuture.supplyAsync(() -> {
            ctx.put("flag", "first");
            return "w1";
        });

        // Writer2 overwrites it
        Task<String, String> writer2 = (in, ctx) -> CompletableFuture.supplyAsync(() -> {
            ctx.put("flag", "second");
            return "w2";
        });

        // Reader observes the last write
        Task<String, String> reader = (in, ctx) -> CompletableFuture.supplyAsync(() -> ctx.get("flag", String.class).orElse("none"));

        pipeline.add("writer1", writer1).then("writer2", writer2).then("reader", reader);

        Object result = pipeline.run("x").join();
        assertEquals("second", result);
    }
}
