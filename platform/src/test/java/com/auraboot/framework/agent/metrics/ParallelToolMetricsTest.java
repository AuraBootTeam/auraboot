package com.auraboot.framework.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ParallelToolMetrics} (ACP P0-5).
 *
 * <p>These exercise the four meters in isolation against a {@link SimpleMeterRegistry}
 * — no Spring context, no StepLoopService — so failures here pinpoint metric
 * shape (name, tag, type) regressions before the StepLoopParallelToolTest
 * integration assertions kick in.
 */
@DisplayName("ParallelToolMetrics (P0-5)")
class ParallelToolMetricsTest {

    private SimpleMeterRegistry registry;
    private ParallelToolMetrics metrics;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        metrics = new ParallelToolMetrics(registry);
    }

    @Test
    @DisplayName("recordFanout_addsTagAndValue")
    void recordFanout_addsTagAndValue() {
        metrics.recordFanout(101L, 3);
        metrics.recordFanout(101L, 5);
        metrics.recordFanout(202L, 2);

        DistributionSummary t101 = registry.find(ParallelToolMetrics.FANOUT_NAME)
                .tag("tenant", "101").summary();
        assertThat(t101).isNotNull();
        assertThat(t101.count()).isEqualTo(2L);
        assertThat(t101.totalAmount()).isEqualTo(8.0);
        assertThat(t101.max()).isEqualTo(5.0);

        DistributionSummary t202 = registry.find(ParallelToolMetrics.FANOUT_NAME)
                .tag("tenant", "202").summary();
        assertThat(t202).isNotNull();
        assertThat(t202.count()).isEqualTo(1L);
        assertThat(t202.totalAmount()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordFanout_nullTenant_fallsBackToUnknownTag")
    void recordFanout_nullTenant_fallsBackToUnknownTag() {
        metrics.recordFanout(null, 4);

        DistributionSummary unknown = registry.find(ParallelToolMetrics.FANOUT_NAME)
                .tag("tenant", "unknown").summary();
        assertThat(unknown).isNotNull();
        assertThat(unknown.count()).isEqualTo(1L);
        assertThat(unknown.totalAmount()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("recordMaxLatency_recordsHistogram")
    void recordMaxLatency_recordsHistogram() {
        metrics.recordMaxLatency(101L, 120L);
        metrics.recordMaxLatency(101L, 80L);

        DistributionSummary summary = registry.find(ParallelToolMetrics.MAX_LATENCY_NAME)
                .tag("tenant", "101").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(2L);
        assertThat(summary.totalAmount()).isEqualTo(200.0);
        assertThat(summary.max()).isEqualTo(120.0);
        assertThat(summary.getId().getBaseUnit()).isEqualTo("ms");
    }

    @Test
    @DisplayName("recordRejected_incrementsCounter")
    void recordRejected_incrementsCounter() {
        metrics.recordRejected(101L);
        metrics.recordRejected(101L);
        metrics.recordRejected(202L);

        Counter t101 = registry.find(ParallelToolMetrics.REJECTED_NAME)
                .tag("tenant", "101").counter();
        assertThat(t101).isNotNull();
        assertThat(t101.count()).isEqualTo(2.0);

        Counter t202 = registry.find(ParallelToolMetrics.REJECTED_NAME)
                .tag("tenant", "202").counter();
        assertThat(t202).isNotNull();
        assertThat(t202.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordTimeout_incrementsCounter")
    void recordTimeout_incrementsCounter() {
        metrics.recordTimeout(101L);
        metrics.recordTimeout(null);

        Counter t101 = registry.find(ParallelToolMetrics.TIMEOUT_NAME)
                .tag("tenant", "101").counter();
        assertThat(t101).isNotNull();
        assertThat(t101.count()).isEqualTo(1.0);

        Counter unknown = registry.find(ParallelToolMetrics.TIMEOUT_NAME)
                .tag("tenant", "unknown").counter();
        assertThat(unknown).isNotNull();
        assertThat(unknown.count()).isEqualTo(1.0);
    }
}
