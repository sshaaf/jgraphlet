package dev.shaaf.jgraphlet;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe key-value store shared across tasks in a pipeline.
 * Tasks can use this to pass data to subsequent tasks without
 * coupling their inputs and outputs directly.
 */
public class PipelineContext {
    private final Map<String, Object> storage = new ConcurrentHashMap<>();

    /**
     * Stores a value under the given key.
     *
     * @param key   the key to associate with the value
     * @param value the value to store; may be null
     */
    public void put(String key, Object value) {
        storage.put(key, value);
    }

    /**
     * Retrieves a value by key and attempts to cast it to the requested type.
     *
     * @param key  the key used to look up the value
     * @param type the expected type of the stored value
     * @param <T>  the type parameter requested by the caller
     * @return an Optional containing the value cast to the requested type if present and compatible; otherwise empty
     */
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = storage.get(key);
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return Optional.empty();
    }
}
