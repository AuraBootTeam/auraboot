package com.auraboot.framework.billing.observability;

import com.auraboot.framework.billing.metering.spi.MeteringResultStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Low-cardinality operational metrics for usage ingestion.
 *
 * <p>No account, tenant, idempotency-key, or resource tags are emitted. Those
 * dimensions are unbounded and belong in the usage ledger, not Prometheus.
 */
@Component
@RequiredArgsConstructor
public class BillingMeteringMetrics {

    public static final String RECORD_TOTAL = "aura_billing_metering_record_total";
    public static final String RECORD_SECONDS = "aura_billing_metering_record_seconds";
    public static final String OUTCOME_FAILED = "failed";

    private final MeterRegistry registry;

    public void record(MeteringResultStatus status, long elapsedNanos) {
        record(status != null ? status.name().toLowerCase() : "unknown", elapsedNanos);
    }

    public void recordFailure(long elapsedNanos) {
        record(OUTCOME_FAILED, elapsedNanos);
    }

    private void record(String outcome, long elapsedNanos) {
        Counter.builder(RECORD_TOTAL)
                .description("Usage metering record attempts by bounded outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder(RECORD_SECONDS)
                .description("Usage metering record latency by bounded outcome")
                .tag("outcome", outcome)
                .register(registry)
                .record(Duration.ofNanos(elapsedNanos));
    }
}
