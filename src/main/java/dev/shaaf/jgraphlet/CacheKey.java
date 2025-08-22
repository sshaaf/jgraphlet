package dev.shaaf.jgraphlet;

/**
 * Cache key used to uniquely identify the output of a task for a given input.
 *
 * @param taskName the name of the task
 * @param input    the input provided to that task
 */
public record CacheKey(String taskName, Object input) {
}