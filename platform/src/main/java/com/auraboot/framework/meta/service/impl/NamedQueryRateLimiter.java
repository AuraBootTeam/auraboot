package com.auraboot.framework.meta.service.impl;

import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/**
 * Sliding-window rate limiter for named query execution.
 * Key = tenantId:queryCode, window = 60 seconds.
 */
@Component
public class NamedQueryRateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public NamedQueryRateLimiter() {
        this(System::currentTimeMillis);
    }

    NamedQueryRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Try to acquire a permit for the given key.
     *
     * @param tenantId tenant ID
     * @param queryCode query code
     * @param maxPerMinute max permitted calls per minute
     * @return true if allowed, false if rate limit exceeded
     */
    public boolean tryAcquire(Long tenantId, String queryCode, int maxPerMinute) {
        if (maxPerMinute <= 0) {
            return true; // unlimited
        }

        String key = tenantId + ":" + queryCode;
        Deque<Long> timestamps = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        // Admission and recording are atomic for this tenant/query window.
        synchronized (timestamps) {
            long now = clock.getAsLong();
            evictExpired(timestamps, now);
            if (timestamps.size() >= maxPerMinute) return false;
            timestamps.addLast(now);
            return true;
        }
    }

    /**
     * Get current count for a key (for monitoring).
     */
    public int getCurrentCount(Long tenantId, String queryCode) {
        String key = tenantId + ":" + queryCode;
        Deque<Long> timestamps = windows.get(key);
        if (timestamps == null) return 0;

        synchronized (timestamps) {
            evictExpired(timestamps, clock.getAsLong());
            return timestamps.size();
        }
    }

    private void evictExpired(Deque<Long> timestamps, long now) {
        long cutoff = now - WINDOW_MS;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) timestamps.pollFirst();
    }
}
