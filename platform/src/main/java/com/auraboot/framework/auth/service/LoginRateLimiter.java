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
 * Sliding-window rate limiter for login attempts.
 * Tracks attempts per IP and per email independently.
 * <p>
 * Defaults: max 3,000 attempts per IP per minute, max 3,000 attempts per email per minute.
 * After exceeding the limit, further attempts are blocked until the window slides.
 * <p>
 * Stale entries are evicted every 5 minutes to prevent unbounded memory growth.
 * If the map exceeds MAX_KEYS, the oldest entries are dropped.
 *
 * @since 7.1.0
 */
@Slf4j
@Component
public class LoginRateLimiter {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_PER_IP = 3_000;
    private static final int MAX_PER_EMAIL = 3_000;
    private static final int MAX_KEYS = 10_000;

    private final Map<String, Deque<Long>> ipWindows = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> emailWindows = new ConcurrentHashMap<>();

    /**
     * Check if a login attempt is allowed.
     *
     * @param ip    client IP address
     * @param email login email/identifier
     * @return true if allowed, false if rate limited
     */
    public boolean isAllowed(String ip, String email) {
        long now = System.currentTimeMillis();

        if (ip != null && !tryAcquire(ipWindows, "ip:" + ip, MAX_PER_IP, now)) {
            log.warn("Login rate limit exceeded for IP: {}", ip);
            return false;
        }

        if (email != null && !tryAcquire(emailWindows, "email:" + email.toLowerCase(), MAX_PER_EMAIL, now)) {
            log.warn("Login rate limit exceeded for email: {}", email);
            return false;
        }

        return true;
    }

    private boolean tryAcquire(Map<String, Deque<Long>> windows, String key, int max, long now) {
        // Prevent unbounded growth: if map is full, reject new keys (existing keys still work)
        if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) {
            log.warn("Rate limiter map full ({} keys), rejecting new key", windows.size());
            return false;
        }

        long cutoff = now - WINDOW_MS;
        Deque<Long> timestamps = windows.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        // Evict expired entries
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= max) {
            return false;
        }

        timestamps.addLast(now);
        return true;
    }

    /**
     * Periodic cleanup: remove entries with no recent activity.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300_000)
    void evictStaleEntries() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        evictFrom(ipWindows, cutoff);
        evictFrom(emailWindows, cutoff);
    }

    private void evictFrom(Map<String, Deque<Long>> windows, long cutoff) {
        Iterator<Map.Entry<String, Deque<Long>>> it = windows.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            Deque<Long> timestamps = it.next().getValue();
            // Evict expired timestamps
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            // Remove empty entries
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
