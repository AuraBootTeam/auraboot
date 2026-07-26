package com.auraboot.framework.billing.observability;

import com.auraboot.framework.billing.metering.spi.MeteringResultStatus;
import com.auraboot.framework.billing.quota.spi.QuotaDecision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BillingCoreMetricsTest {

    @Test
    void metering_records_every_bounded_outcome_and_latency() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BillingMeteringMetrics metrics = new BillingMeteringMetrics(registry);

        metrics.record(MeteringResultStatus.ACCEPTED, 1_000L);
        metrics.record(MeteringResultStatus.DUPLICATE_IGNORED, 2_000L);
        metrics.record(MeteringResultStatus.CONFLICT, 3_000L);
        metrics.record(MeteringResultStatus.REJECTED, 4_000L);
        metrics.recordFailure(5_000L);

        for (String outcome : new String[]{
                "accepted", "duplicate_ignored", "conflict", "rejected", "failed"}) {
            assertThat(registry.find(BillingMeteringMetrics.RECORD_TOTAL)
                    .tag("outcome", outcome)
                    .counter())
                    .isNotNull()
                    .extracting(counter -> counter.count())
                    .isEqualTo(1.0d);
            assertThat(registry.find(BillingMeteringMetrics.RECORD_SECONDS)
                    .tag("outcome", outcome)
                    .timer())
                    .isNotNull()
                    .extracting(timer -> timer.count())
                    .isEqualTo(1L);
        }
    }

    @Test
    void quota_records_allow_deny_failure_and_optimistic_retry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BillingQuotaMetrics metrics = new BillingQuotaMetrics(registry);

        metrics.recordAuthorization(QuotaDecision.allow("RES-1", null), 1_000L);
        metrics.recordAuthorization(
                QuotaDecision.deny("INSUFFICIENT_QUOTA"), 2_000L);
        metrics.recordAuthorizationFailure(3_000L);
        metrics.recordCommit(false, 4_000L);
        metrics.recordCommit(true, 5_000L);
        metrics.recordCommitFailure(6_000L);
        metrics.recordOptimisticRetry("reserve");

        assertThat(counter(
                registry, "allow", "none")).isEqualTo(1.0d);
        assertThat(counter(
                registry, "deny", "insufficient_quota")).isEqualTo(1.0d);
        assertThat(counter(
                registry, "failed", "exception")).isEqualTo(1.0d);
        for (String outcome : new String[]{"success", "capped", "failed"}) {
            assertThat(registry.find(BillingQuotaMetrics.COMMIT_TOTAL)
                    .tag("outcome", outcome)
                    .counter())
                    .isNotNull()
                    .extracting(counter -> counter.count())
                    .isEqualTo(1.0d);
            assertThat(registry.find(BillingQuotaMetrics.COMMIT_SECONDS)
                    .tag("outcome", outcome)
                    .timer())
                    .isNotNull()
                    .extracting(timer -> timer.count())
                    .isEqualTo(1L);
        }
        assertThat(registry.find(BillingQuotaMetrics.OPTIMISTIC_RETRY_TOTAL)
                .tag("operation", "reserve")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    private double counter(SimpleMeterRegistry registry, String outcome, String reason) {
        return registry.find(BillingQuotaMetrics.AUTHORIZE_TOTAL)
                .tag("outcome", outcome)
                .tag("reason", reason)
                .counter()
                .count();
    }
}
