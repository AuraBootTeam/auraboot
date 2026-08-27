package com.auraboot.framework.integration;

import java.time.Duration;

/** Deterministic bounded exponential retry policy for reliable integration delivery. */
public final class IntegrationBackoffPolicy {

    private final Duration baseDelay;
    private final Duration maximumDelay;

    public IntegrationBackoffPolicy(Duration baseDelay, Duration maximumDelay) {
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maximumDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maximumDelay must not be less than baseDelay");
        }
        this.baseDelay = baseDelay;
        this.maximumDelay = maximumDelay;
    }

    public Duration delayForRetry(int completedRetries) {
        if (completedRetries < 0) {
            throw new IllegalArgumentException("completedRetries must be non-negative");
        }
        int safeShift = Math.min(completedRetries, 30);
        long multiplier = 1L << safeShift;
        try {
            Duration candidate = baseDelay.multipliedBy(multiplier);
            return candidate.compareTo(maximumDelay) > 0 ? maximumDelay : candidate;
        } catch (ArithmeticException overflow) {
            return maximumDelay;
        }
    }
}
