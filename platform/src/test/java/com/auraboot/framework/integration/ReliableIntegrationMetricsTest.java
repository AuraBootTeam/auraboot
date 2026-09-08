package com.auraboot.framework.integration;

import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReliableIntegrationMetricsTest {

    @Test
    void exportsBoundedCountersAndDatabaseBackedHealthGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        when(mapper.countReadyEvents()).thenReturn(3L);
        when(mapper.countOpenDeadLetters()).thenReturn(1L);
        when(mapper.countExpiredLeases()).thenReturn(0L);
        when(mapper.oldestUndeliveredAgeSeconds()).thenReturn(301L);

        ReliableIntegrationMetrics metrics = new ReliableIntegrationMetrics(registry, mapper);
        metrics.record("retry_scheduled");
        metrics.record("attacker-controlled-value");

        assertThat(registry.get("auraboot_reliable_delivery_pending").gauge().value()).isEqualTo(3);
        assertThat(registry.get("auraboot_reliable_delivery_dlq").gauge().value()).isEqualTo(1);
        assertThat(registry.get("auraboot_reliable_delivery_oldest_pending_age_seconds").gauge().value())
                .isEqualTo(301);
        assertThat(registry.get("auraboot_reliable_delivery_unhealthy").gauge().value()).isEqualTo(1);
        assertThat(registry.get("auraboot_integration_events_total")
                .tag("outcome", "retry_scheduled").counter().count()).isEqualTo(1);
        assertThat(registry.get("auraboot_integration_events_total")
                .tag("outcome", "other").counter().count()).isEqualTo(1);
        assertThat(registry.find("auraboot_integration_events_total")
                .tag("outcome", "attacker-controlled-value").counter()).isNull();
    }

    @Test
    void datasourceFailureIsVisibleAsNanInsteadOfHealthyZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        when(mapper.countReadyEvents()).thenThrow(new IllegalStateException("database unavailable"));

        new ReliableIntegrationMetrics(registry, mapper);

        assertThat(registry.get("auraboot_reliable_delivery_pending").gauge().value()).isNaN();
    }
}
