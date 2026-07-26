package com.auraboot.framework.billing.observability;

import com.auraboot.framework.billing.quota.spi.QuotaDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Quota authorization outcome/latency and optimistic-lock retry metrics.
 */
@Component
@RequiredArgsConstructor
public class BillingQuotaMetrics {

    public static final String AUTHORIZE_TOTAL = "aura_billing_quota_authorize_total";
    public static final String AUTHORIZE_SECONDS = "aura_billing_quota_authorize_seconds";
    public static final String COMMIT_TOTAL = "aura_billing_quota_commit_total";
    public static final String COMMIT_SECONDS = "aura_billing_quota_commit_seconds";
    public static final String OPTIMISTIC_RETRY_TOTAL =
            "aura_billing_quota_optimistic_retry_total";

    private static final Set<String> DENY_REASONS =
            Set.of("RESOURCE_NOT_REGISTERED", "INSUFFICIENT_QUOTA");

    private final MeterRegistry registry;

    public void recordAuthorization(QuotaDecision decision, long elapsedNanos) {
        String outcome = decision != null && decision.isAllowed() ? "allow" : "deny";
        String reason = decision != null && !decision.isAllowed()
                ? boundedReason(decision.getDenyReason())
                : "none";
        recordAuthorization(outcome, reason, elapsedNanos);
    }

    public void recordAuthorizationFailure(long elapsedNanos) {
        recordAuthorization("failed", "exception", elapsedNanos);
    }

    public void recordCommit(boolean capped, long elapsedNanos) {
        recordCommit(capped ? "capped" : "success", elapsedNanos);
    }

    public void recordCommitFailure(long elapsedNanos) {
        recordCommit("failed", elapsedNanos);
    }

    public void recordOptimisticRetry(String operation) {
        Counter.builder(OPTIMISTIC_RETRY_TOTAL)
                .description("Quota optimistic-lock retries by bounded operation")
                .tag("operation", operation)
                .register(registry)
                .increment();
    }

    private void recordAuthorization(String outcome, String reason, long elapsedNanos) {
        Counter.builder(AUTHORIZE_TOTAL)
                .description("Quota authorization attempts by outcome and bounded deny reason")
                .tag("outcome", outcome)
                .tag("reason", reason)
                .register(registry)
                .increment();
        Timer.builder(AUTHORIZE_SECONDS)
                .description("Quota authorization latency by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .record(Duration.ofNanos(elapsedNanos));
    }

    private void recordCommit(String outcome, long elapsedNanos) {
        Counter.builder(COMMIT_TOTAL)
                .description("Quota commit attempts by bounded outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder(COMMIT_SECONDS)
                .description("Quota commit latency by bounded outcome")
                .tag("outcome", outcome)
                .register(registry)
                .record(Duration.ofNanos(elapsedNanos));
    }

    private String boundedReason(String reason) {
        return DENY_REASONS.contains(reason) ? reason.toLowerCase() : "other";
    }
}
