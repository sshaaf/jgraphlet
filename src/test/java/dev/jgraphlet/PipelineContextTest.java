package dev.jgraphlet;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PipelineContextTest {

    /**
     * Tests for the `get` method in the `PipelineContext` class.
     * The `get` method retrieves a value from the context storage based on the provided key and type.
     * Returns an `Optional<T>` if the value exists and matches the specified type; otherwise, an empty Optional.
     */

    @Test
    void testGetExistingKeyWithMatchingType() {
        PipelineContext context = new PipelineContext();
        context.put("testKey", 42);
        Optional<Integer> result = context.get("testKey", Integer.class);

        assertTrue(result.isPresent());
        assertEquals(42, result.get());
    }

    @Test
    void testGetExistingKeyWithNonMatchingType() {
        PipelineContext context = new PipelineContext();
        context.put("testKey", 42);
        Optional<String> result = context.get("testKey", String.class);

        assertFalse(result.isPresent());
    }

    @Test
    void testGetNonExistingKey() {
        PipelineContext context = new PipelineContext();
        Optional<Integer> result = context.get("nonExistentKey", Integer.class);

        assertFalse(result.isPresent());
    }

    @Test
    void testGetWithNullType() {
        PipelineContext context = new PipelineContext();
        context.put("testKey", "value");

        assertThrows(NullPointerException.class, () -> context.get("testKey", null));
    }

    @Test
    void testGetWithNullKey() {
        PipelineContext context = new PipelineContext();
        context.put("testKey", "value");

        assertThrows(NullPointerException.class, () -> context.get(null, String.class));
    }
}