package com.auraboot.framework.connector.saas.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retry / back-off / rate-limit composition for {@link SaasHttpClient}. */
class SaasHttpClientTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Deque<SaasHttpResponse> scripted = new ArrayDeque<>();
    private final Deque<SaasHttpException> scriptedFailures = new ArrayDeque<>();
    private final AtomicInteger callCount = new AtomicInteger(0);
    private final SaasRateLimiter rateLimiter = new SaasRateLimiter(
            () -> 0L, ms -> {}); // zero-cost limiter
    private final java.util.List<Long> sleeps = new java.util.ArrayList<>();

    private SaasHttpExecutor executor;
    private SaasHttpClient client;

    @BeforeEach
    void setup() {
        executor = req -> {
            callCount.incrementAndGet();
            if (!scriptedFailures.isEmpty()) {
                throw scriptedFailures.pop();
            }
            if (scripted.isEmpty()) {
                throw new AssertionError("Unexpected extra HTTP call");
            }
            return scripted.pop();
        };
        client = new SaasHttpClient(executor, rateLimiter, json,
                ms -> sleeps.add(ms));
    }

    private static SaasHttpResponse ok(String json) {
        return new SaasHttpResponse(200,
                Map.of("Content-Type", List.of("application/json")),
                json.getBytes());
    }

    private static SaasHttpResponse status(int code, Map<String, List<String>> headers) {
        return new SaasHttpResponse(code, headers, new byte[0]);
    }

    private SaasHttpRequest req() {
        return SaasHttpRequest.builder()
                .tenantId(1L)
                .vendor("saas-test")
                .method("GET")
                .url("https://api.example.com/v1/x")
                .build();
    }

    // -- happy path ------------------------------------------------------

    @Test
    void singleSuccessCall() {
        scripted.push(ok("{\"ok\":true}"));
        SaasHttpResponse resp = client.execute(req(),
                SaasHttpClient.RetryPolicy.DEFAULT, SaasHttpClient.RateLimit.HUBSPOT);
        assertThat(resp.isSuccess()).isTrue();
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void executeForJsonReturnsParsedNode() {
        scripted.push(ok("{\"value\":42}"));
        var node = client.executeForJson(req(),
                SaasHttpClient.RetryPolicy.DEFAULT, SaasHttpClient.RateLimit.HUBSPOT);
        assertThat(node.get("value").asInt()).isEqualTo(42);
    }

    @Test
    void executeForJsonThrowsOn4xx() {
        scripted.push(new SaasHttpResponse(403,
                Map.of(), "{\"error\":\"forbidden\"}".getBytes()));
        assertThatThrownBy(() -> client.executeForJson(req(),
                SaasHttpClient.RetryPolicy.NONE, SaasHttpClient.RateLimit.HUBSPOT))
                .isInstanceOf(SaasHttpException.class)
                .hasMessageContaining("Non-success status: 403")
                .hasMessageContaining("forbidden");
    }

    // -- retry on 5xx ---------------------------------------------------

    @Test
    void retriesOn500ThenSucceeds() {
        // Scripted is a stack; push reverse-order.
        scripted.push(ok("{\"ok\":true}"));
        scripted.push(status(503, Map.of()));
        scripted.push(status(500, Map.of()));
        SaasHttpResponse resp = client.execute(req(),
                SaasHttpClient.RetryPolicy.DEFAULT, SaasHttpClient.RateLimit.HUBSPOT);
        assertThat(resp.isSuccess()).isTrue();
        assertThat(callCount.get()).isEqualTo(3);
        // 2 retries → 2 sleeps with exponential back-off
        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(0)).isEqualTo(500L);   // initial
        assertThat(sleeps.get(1)).isEqualTo(1_000L); // 2x
    }

    @Test
    void retriesExhaustedReturnsLastResponse() {
        for (int i = 0; i < 3; i++) scripted.push(status(500, Map.of()));
        SaasHttpClient.RetryPolicy retry =
                new SaasHttpClient.RetryPolicy(3, 100L, 1_000L);
        SaasHttpResponse resp = client.execute(req(), retry,
                SaasHttpClient.RateLimit.HUBSPOT);
        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(callCount.get()).isEqualTo(3);
        assertThat(sleeps).hasSize(2); // 2 inter-attempt sleeps
    }

    // -- 429 + Retry-After ---------------------------------------------

    @Test
    void retryAfterHeaderOverridesExponentialBackoff() {
        scripted.push(ok("{\"ok\":true}"));
        scripted.push(status(429,
                Map.of("Retry-After", List.of("2"))));
        client.execute(req(),
                SaasHttpClient.RetryPolicy.DEFAULT, SaasHttpClient.RateLimit.HUBSPOT);
        assertThat(callCount.get()).isEqualTo(2);
        assertThat(sleeps).containsExactly(2_000L);
    }

    @Test
    void retryAfterCappedAtMaxBackoff() {
        scripted.push(ok("{}"));
        scripted.push(status(429,
                Map.of("Retry-After", List.of("9999"))));
        client.execute(req(),
                new SaasHttpClient.RetryPolicy(2, 100L, 3_000L),
                SaasHttpClient.RateLimit.HUBSPOT);
        assertThat(sleeps).containsExactly(3_000L);
    }

    @Test
    void retryAfterMissingFallsBackToExponential() {
        scripted.push(ok("{}"));
        scripted.push(status(429, Map.of()));
        client.execute(req(),
                new SaasHttpClient.RetryPolicy(2, 250L, 30_000L),
                SaasHttpClient.RateLimit.HUBSPOT);
        assertThat(sleeps).containsExactly(250L);
    }

    // -- transport failures --------------------------------------------

    @Test
    void transportFailureIsRetried() {
        scripted.push(ok("{\"ok\":true}"));
        scriptedFailures.push(new SaasHttpException("ECONNREFUSED"));
        SaasHttpResponse resp = client.execute(req(),
                SaasHttpClient.RetryPolicy.DEFAULT, SaasHttpClient.RateLimit.HUBSPOT);
        assertThat(resp.isSuccess()).isTrue();
        assertThat(callCount.get()).isEqualTo(2);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void transportFailureRetriesExhaustedRethrows() {
        scriptedFailures.push(new SaasHttpException("ECONNREFUSED 1"));
        scriptedFailures.push(new SaasHttpException("ECONNREFUSED 2"));
        SaasHttpClient.RetryPolicy retry =
                new SaasHttpClient.RetryPolicy(2, 100L, 1_000L);
        assertThatThrownBy(() -> client.execute(req(), retry,
                SaasHttpClient.RateLimit.HUBSPOT))
                .isInstanceOf(SaasHttpException.class)
                .hasMessageContaining("ECONNREFUSED 1");
        assertThat(callCount.get()).isEqualTo(2);
    }

    // -- listener -------------------------------------------------------

    @Test
    void retryListenerObservesEachRetry() {
        List<String> events = new java.util.ArrayList<>();
        client.setListener((req, attempt, status, ex, sleepMs) ->
                events.add("attempt=" + attempt + " status=" + status
                        + " sleep=" + sleepMs));
        scripted.push(ok("{}"));
        scripted.push(status(500, Map.of()));
        scripted.push(status(429, Map.of("Retry-After", List.of("1"))));
        client.execute(req(),
                SaasHttpClient.RetryPolicy.DEFAULT, SaasHttpClient.RateLimit.HUBSPOT);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).contains("attempt=1").contains("status=429").contains("sleep=1000");
        assertThat(events.get(1)).contains("attempt=2").contains("status=500");
    }

    // -- rate limit integration ----------------------------------------

    @Test
    void rateLimitAcquiredOnEachAttempt() {
        scripted.push(ok("{}"));
        scripted.push(status(500, Map.of()));
        client.execute(req(),
                new SaasHttpClient.RetryPolicy(2, 100L, 1_000L),
                SaasHttpClient.RateLimit.HUBSPOT);
        // 2 attempts → 2 tokens consumed.
        assertThat(rateLimiter.currentSize(1L, "saas-test")).isEqualTo(2);
    }

    @Test
    void nullRateLimitSkipsAcquire() {
        scripted.push(ok("{}"));
        client.execute(req(), SaasHttpClient.RetryPolicy.DEFAULT, null);
        assertThat(rateLimiter.currentSize(1L, "saas-test")).isZero();
    }
}
