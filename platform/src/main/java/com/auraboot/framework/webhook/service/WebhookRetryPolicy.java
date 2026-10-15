package com.auraboot.framework.webhook.service;

import com.auraboot.framework.integration.IntegrationBackoffPolicy;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongBinaryOperator;

/** Bounded exponential backoff with equal jitter for independently retried deliveries. */
public final class WebhookRetryPolicy {
    private final IntegrationBackoffPolicy exponential =
            new IntegrationBackoffPolicy(Duration.ofSeconds(1), Duration.ofHours(1));
    private final LongBinaryOperator sampleMillis;

    public WebhookRetryPolicy() {
        this((origin, bound) -> ThreadLocalRandom.current().nextLong(origin, bound));
    }

    WebhookRetryPolicy(LongBinaryOperator sampleMillis) {
        this.sampleMillis = Objects.requireNonNull(sampleMillis);
    }

    public Duration delayForRetry(int completedRetries) {
        long ceilingMillis = exponential.delayForRetry(completedRetries).toMillis();
        long floorMillis = ceilingMillis / 2;
        long delayMillis = sampleMillis.applyAsLong(floorMillis, ceilingMillis);
        if (delayMillis < floorMillis || delayMillis >= ceilingMillis) {
            throw new IllegalArgumentException("Webhook retry sample outside equal-jitter bounds");
        }
        return Duration.ofMillis(delayMillis);
    }
}
