package com.auraboot.framework.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * General-purpose sliding-window rate limiter for API endpoints.
 * <p>
 * Tracks attempts per key (IP, email, etc.) within a configurable window.
 * Each logical operation gets its own namespace to avoid cross-contamination.
 * <p>
 * Usage:
 * <pre>
 *   rateLimiter.isAllowed("login:ip:" + ip, 10);       // 10 per minute per IP for login
 *   rateLimiter.isAllowed("login:email:" + email, 5);   // 5 per minute per email for login
 *   rateLimiter.isAllowed("forgot-pwd:ip:" + ip, 3);    // 3 per minute per IP for password reset
 *   rateLimiter.isAllowed("reset-pwd:ip:" + ip, 5);     // 5 per minute per IP for token reset
 * </pre>
 */
@Slf4j
@Component
public class ApiRateLimiter {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_KEYS = 50_000;

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * Check if an operation is allowed within the rate limit.
     *
     * @param key namespaced key (e.g. "login:ip:1.2.3.4")
     * @param max maximum allowed attempts per window
     * @return true if allowed, false if rate limited
     */
    public boolean isAllowed(String key, int max) {
        long now = System.currentTimeMillis();

        if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) {
            log.warn("Rate limiter map full ({} keys), rejecting new key: {}", windows.size(), key.split(":")[0]);
            return false;
        }

        long cutoff = now - WINDOW_MS;
        Deque<Long> timestamps = windows.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= max) {
            log.warn("Rate limit exceeded for key: {}", key);
            return false;
        }

        timestamps.addLast(now);
        return true;
    }

    @Scheduled(fixedRate = 300_000)
    void evictStaleEntries() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        Iterator<Map.Entry<String, Deque<Long>>> it = windows.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            Deque<Long> timestamps = it.next().getValue();
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.isEmpty()) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Rate limiter evicted {} stale entries, remaining: {}", removed, windows.size());
        }
    }
}
