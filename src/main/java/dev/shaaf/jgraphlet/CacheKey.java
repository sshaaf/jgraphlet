package dev.shaaf.jgraphlet;

import java.util.*;

/**
 * Cache key used to uniquely identify the output of a task for a given input.
 *
 * @param taskName the name of the task
 * @param input    the input provided to that task
 */
public record CacheKey(String taskName, Object input) {
  public CacheKey {
    Objects.requireNonNull(taskName, "taskName");
    input = normalize(input); // snapshot for stable equals/hashCode
  }
  private static Object normalize(Object o) {
    if (o == null) return null;
    if (o instanceof Map<?, ?> m) {
      // preserve iteration order to keep equals/hash stable
      var copy = new LinkedHashMap<>(m);
      return Map.copyOf(copy);
    }
    if (o instanceof List<?> l) return List.copyOf(l);
    if (o instanceof Set<?> s)  return Set.copyOf(s);
    return o; // assume immutable or stable
  }
}
