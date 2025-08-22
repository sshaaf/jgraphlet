package dev.shaaf.jgraphlet;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class PipelineContext {
    private final Map<String, Object> storage = new ConcurrentHashMap<>();

    public void put(String key, Object value) {
        storage.put(key, value);
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = storage.get(key);
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return Optional.empty();
    }
}
