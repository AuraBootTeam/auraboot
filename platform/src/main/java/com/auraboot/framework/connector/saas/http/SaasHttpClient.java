package com.auraboot.framework.connector.saas.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * High-level SaaS HTTP client. Drives the SPI {@link SaasHttpExecutor}, the
 * shared {@link SaasRateLimiter}, and a retry/back-off policy honouring
 * {@code Retry-After} hints. PRD 18 §B.3.2.
 *
 * <p>Vendor adapters MUST go through this class — never call
 * {@link SaasHttpExecutor} directly — so cross-vendor concerns
 * (rate limit, retries, audit) stay in one place.
 *
 * <p>Retry policy:
 * <ul>
 *   <li>Transport failures ({@link SaasHttpException}): retried up to
 *       {@link RetryPolicy#maxAttempts} - 1 times with exponential back-off
 *       capped at {@link RetryPolicy#maxBackoffMs}.</li>
 *   <li>5xx responses: same retry curve.</li>
 *   <li>429 responses: honour {@code Retry-After} when present, otherwise
 *       fall back to the exponential curve.</li>
 *   <li>4xx (other) responses: returned as-is, no retry.</li>
 * </ul>
 *
 * <p>Retries are observed via {@link RetryListener} so callers can audit
 * fallback usage without parsing logs.
 */
@Slf4j
@Component
public class SaasHttpClient {

    private final SaasHttpExecutor executor;
    private final SaasRateLimiter rateLimiter;
    private final ObjectMapper jsonMapper;
    private final Sleeper sleeper;
    private RetryListener listener = RetryListener.NOOP;

    @org.springframework.beans.factory.annotation.Autowired
    public SaasHttpClient(SaasHttpExecutor executor,
                          SaasRateLimiter rateLimiter,
                          ObjectMapper jsonMapper) {
        this(executor, rateLimiter, jsonMapper, Thread::sleep);
    }

    /** Test seam — inject a deterministic sleeper. */
    SaasHttpClient(SaasHttpExecutor executor,
                   SaasRateLimiter rateLimiter,
                   ObjectMapper jsonMapper,
                   Sleeper sleeper) {
        this.executor = executor;
        this.rateLimiter = rateLimiter;
        this.jsonMapper = jsonMapper;
        this.sleeper = sleeper;
    }

    public void setListener(RetryListener l) {
        this.listener = l == null ? RetryListener.NOOP : l;
    }

    public SaasHttpResponse execute(SaasHttpRequest req, RetryPolicy retry, RateLimit limit) {
        if (retry == null) retry = RetryPolicy.DEFAULT;
        SaasHttpException lastTransport = null;
        SaasHttpResponse lastResponse = null;
        for (int attempt = 1; attempt <= retry.maxAttempts; attempt++) {
            try {
                if (limit != null) {
                    rateLimiter.acquire(req.tenantId(), req.vendor(),
                            limit.maxRequests, limit.windowMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SaasHttpException("Interrupted while waiting for rate-limit token", e);
            }
            try {
                SaasHttpResponse resp = executor.execute(req);
                if (!resp.isRetryable() || attempt == retry.maxAttempts) {
                    return resp;
                }
                long sleepMs = resolveBackoff(resp.retryAfterSeconds().orElse(null),
                        attempt, retry);
                listener.onRetry(req, attempt, resp.statusCode(), null, sleepMs);
                sleepQuietly(sleepMs);
                lastResponse = resp;
            } catch (SaasHttpException transport) {
                if (attempt == retry.maxAttempts) {
                    throw transport;
                }
                long sleepMs = resolveBackoff(null, attempt, retry);
                listener.onRetry(req, attempt, -1, transport, sleepMs);
                sleepQuietly(sleepMs);
                lastTransport = transport;
            }
        }
        if (lastResponse != null) return lastResponse;
        throw lastTransport != null ? lastTransport
                : new SaasHttpException("Exhausted retries with no response captured");
    }

    public JsonNode executeForJson(SaasHttpRequest req, RetryPolicy retry, RateLimit limit) {
        SaasHttpResponse resp = execute(req, retry, limit);
        if (!resp.isSuccess()) {
            throw new SaasHttpException("Non-success status: " + resp.statusCode()
                    + " body=" + truncate(resp.bodyAsString(), 200), resp.statusCode());
        }
        return resp.json(jsonMapper);
    }

    private static long resolveBackoff(Integer retryAfterSeconds, int attempt, RetryPolicy retry) {
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return Math.min(retry.maxBackoffMs, retryAfterSeconds * 1000L);
        }
        long pow = 1L << Math.min(attempt - 1, 16); // cap shift
        long base = retry.initialBackoffMs * pow;
        return Math.min(retry.maxBackoffMs, base);
    }

    private void sleepQuietly(long ms) {
        try { sleeper.sleep(Math.max(1L, ms)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // -- value types ----------------------------------------------------

    public record RetryPolicy(int maxAttempts, long initialBackoffMs, long maxBackoffMs) {
        public RetryPolicy {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts >= 1");
            if (initialBackoffMs < 1) throw new IllegalArgumentException("initialBackoffMs >= 1");
            if (maxBackoffMs < initialBackoffMs)
                throw new IllegalArgumentException("maxBackoffMs >= initialBackoffMs");
        }
        public static final RetryPolicy DEFAULT = new RetryPolicy(5, 500L, 30_000L);
        public static final RetryPolicy NONE = new RetryPolicy(1, 1L, 1L);
    }

    public record RateLimit(int maxRequests, long windowMs) {
        public static final RateLimit HUBSPOT = new RateLimit(100, 10_000L);
        public static final RateLimit STRIPE = new RateLimit(100, 1_000L);
        public static final RateLimit SHOPIFY = new RateLimit(2, 1_000L);
    }

    /** Test seam shared with {@link SaasRateLimiter}. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    public interface RetryListener {
        RetryListener NOOP = (req, attempt, status, ex, sleepMs) -> {};
        void onRetry(SaasHttpRequest req, int attempt, int status,
                     SaasHttpException ex, long sleepMs);
    }
}
