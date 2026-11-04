package com.sentinel.util;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Generic time-bounded sliding window backed by an {@link ArrayDeque}.
 * Provides O(1) amortized eviction of expired entries based on a timestamp extractor.
 *
 * @param <T> the type of elements in the window
 */
public class SlidingWindow<T> {

    private final Deque<T> deque;
    private final long windowMs;
    private final TimestampExtractor<T> extractor;

    @FunctionalInterface
    public interface TimestampExtractor<T> {
        long getTimestamp(T item);
    }

    /**
     * Creates a new sliding window.
     *
     * @param windowMs  the window size in milliseconds
     * @param extractor function to extract the timestamp from an element
     */
    public SlidingWindow(long windowMs, TimestampExtractor<T> extractor) {
        this.deque = new ArrayDeque<>();
        this.windowMs = windowMs;
        this.extractor = extractor;
    }

    /**
     * Adds an element to the window and evicts expired entries.
     *
     * @param item the element to add
     */
    public void add(T item) {
        deque.addLast(item);
        evict(extractor.getTimestamp(item));
    }

    /**
     * Evicts all entries older than the window based on the reference timestamp.
     *
     * @param nowMs the current reference timestamp in milliseconds
     * @return the number of evicted entries
     */
    public int evict(long nowMs) {
        long cutoff = nowMs - windowMs;
        int count = 0;
        while (!deque.isEmpty() && extractor.getTimestamp(deque.peekFirst()) < cutoff) {
            deque.pollFirst();
            count++;
        }
        return count;
    }

    /**
     * Returns the number of elements currently in the window.
     */
    public int size() {
        return deque.size();
    }

    /**
     * Returns true if the window is empty.
     */
    public boolean isEmpty() {
        return deque.isEmpty();
    }

    /**
     * Returns the underlying deque for iteration. Do not modify directly.
     */
    public Deque<T> getDeque() {
        return deque;
    }

    /**
     * Clears all elements from the window.
     */
    public void clear() {
        deque.clear();
    }
}
