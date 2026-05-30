package com.auraboot.framework.connector.saas.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tenant per-vendor sliding-window rate limiter for SaaS HTTP calls.
 *
 * <p>Keyed by {@code tenantId + ":" + vendor} so a noisy tenant cannot starve
 * quieter ones on the same connector. Buckets carry a vendor-specific
 * {@code (maxRequests, windowMs)} pair — HubSpot 100/10s, Stripe 100/s,
 * Shopify 2/s with burst 40, etc.
 *
 * <p>Thread-safety: each bucket synchronises around the {@code Deque}, but
 * different buckets proceed in parallel. {@link #acquire} blocks the calling
 * thread when the bucket is full, sleeping until the oldest entry expires +
 * one millisecond. Tests pin {@link Clock} so the wait can be observed
 * deterministically.
 */
@Slf4j
@Component
public class SaasRateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Sleeper sleeper;

    public SaasRateLimiter() {
        this(Clock.systemUtc(), Thread::sleep);
    }

    /** Test seam. */
    public SaasRateLimiter(Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Block the caller until a token is available, then consume it.
     *
     * @param tenantId    null is allowed for system-level calls
     * @param vendor      MUST match the descriptor vendor key
     * @param maxRequests bucket capacity
     * @param windowMs    window length in ms
     */
    public void acquire(Long tenantId, String vendor, int maxRequests, long windowMs)
            throws InterruptedException {
        if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be > 0");
        if (windowMs <= 0) throw new IllegalArgumentException("windowMs must be > 0");
        String key = key(tenantId, vendor);
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());

        while (true) {
            long sleepMs;
            synchronized (bucket) {
                long now = clock.nowMillis();
                long cutoff = now - windowMs;
                while (!bucket.timestamps.isEmpty() && bucket.timestamps.peekFirst() < cutoff) {
                    bucket.timestamps.pollFirst();
                }
                if (bucket.timestamps.size() < maxRequests) {
                    bucket.timestamps.addLast(now);
                    return;
                }
                // Wait until the oldest entry expires.
                long oldest = bucket.timestamps.peekFirst();
                sleepMs = (oldest + windowMs) - now + 1L;
            }
            log.debug("SaasRateLimiter[{}] full ({}/{}), sleeping {}ms",
                    key, maxRequests, maxRequests, sleepMs);
            sleeper.sleep(Math.max(1L, sleepMs));
        }
    }

    /** Inspect current bucket size — for tests + metrics. */
    public int currentSize(Long tenantId, String vendor) {
        Bucket b = buckets.get(key(tenantId, vendor));
        if (b == null) return 0;
        synchronized (b) {
            return b.timestamps.size();
        }
    }

    /** Test seam — clear all buckets between cases. */
    public void reset() { buckets.clear(); }

    private static String key(Long tenantId, String vendor) {
        return (tenantId == null ? "system" : tenantId) + ":" + vendor;
    }

    private static final class Bucket {
        final Deque<Long> timestamps = new ArrayDeque<>();
    }

    // -- test seams ------------------------------------------------------

    @FunctionalInterface
    public interface Clock {
        long nowMillis();
        static Clock systemUtc() { return System::currentTimeMillis; }
        static Clock fixed(Instant t) { return () -> t.toEpochMilli(); }
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }
}
