package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.auraboot.framework.behavior.entity.BehaviorQuarantine;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.behavior.mapper.BehaviorQuarantineMapper;
import com.auraboot.framework.behavior.service.BehaviorCollectService;
import com.auraboot.framework.infrastructure.mq.memory.InMemoryMqProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BehaviorIngestMetricsTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearMetaContext() {
        MetaContext.clear();
    }

    @Test
    void recordsBehaviorIngestCountersAndLagGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BehaviorIngestMetrics metrics = new BehaviorIngestMetrics(registry);

        metrics.recordAccepted("authenticated", 2);
        metrics.recordEnqueued(BehaviorIngestPublisher.TOPIC_EVENTS, 2);
        metrics.recordPersisted("inserted");
        metrics.recordPersisted("duplicate");
        metrics.recordQuarantined("malformed_missing_event_id");
        metrics.recordPublishFailure(BehaviorIngestPublisher.TOPIC_EVENTS, "runtime");
        metrics.recordConsumerLag(BehaviorIngestPublisher.TOPIC_EVENTS, "aura-behavior-ingest", 42);

        assertThat(registry.get("auraboot_behavior_ingest_accepted_total")
                .tag("path", "authenticated").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("auraboot_behavior_ingest_enqueued_total")
                .tag("topic", BehaviorIngestPublisher.TOPIC_EVENTS).counter().count()).isEqualTo(2.0);
        assertThat(registry.get("auraboot_behavior_ingest_persisted_total")
                .tag("outcome", "inserted").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("auraboot_behavior_ingest_persisted_total")
                .tag("outcome", "duplicate").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("auraboot_behavior_ingest_quarantined_total")
                .tag("reason", "malformed_missing_event_id").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("auraboot_behavior_ingest_publish_failures_total")
                .tag("topic", BehaviorIngestPublisher.TOPIC_EVENTS)
                .tag("error", "runtime").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("auraboot_behavior_ingest_consumer_lag")
                .tag("topic", BehaviorIngestPublisher.TOPIC_EVENTS)
                .tag("consumer_group", "aura-behavior-ingest").gauge().value()).isEqualTo(42.0);

        metrics.recordConsumerLag(BehaviorIngestPublisher.TOPIC_EVENTS, "aura-behavior-ingest", 0);
        assertThat(registry.get("auraboot_behavior_ingest_consumer_lag")
                .tag("topic", BehaviorIngestPublisher.TOPIC_EVENTS)
                .tag("consumer_group", "aura-behavior-ingest").gauge().value()).isZero();
    }

    @Test
    void prometheusScrapeContainsBehaviorIngestMetricNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        BehaviorIngestMetrics metrics = new BehaviorIngestMetrics(registry);

        metrics.recordAccepted("keyed", 1);
        metrics.recordEnqueued(BehaviorIngestPublisher.TOPIC_EVENTS, 1);
        metrics.recordPersisted("inserted");
        metrics.recordQuarantined("constraint_violation");
        metrics.recordPublishFailure(BehaviorIngestPublisher.TOPIC_QUARANTINE, "serialization");
        metrics.recordConsumerLag(BehaviorIngestPublisher.TOPIC_EVENTS, "aura-behavior-ingest", 7);

        String scrape = registry.scrape();
        assertThat(scrape).contains("auraboot_behavior_ingest_accepted_total");
        assertThat(scrape).contains("auraboot_behavior_ingest_enqueued_total");
        assertThat(scrape).contains("auraboot_behavior_ingest_persisted_total");
        assertThat(scrape).contains("auraboot_behavior_ingest_quarantined_total");
        assertThat(scrape).contains("auraboot_behavior_ingest_publish_failures_total");
        assertThat(scrape).contains("auraboot_behavior_ingest_consumer_lag");
    }

    @Test
    void collectAndPublisherRecordAcceptedAndEnqueuedMetricsOnRealMqPath() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BehaviorIngestMetrics metrics = new BehaviorIngestMetrics(registry);
        BehaviorIngestPublisher publisher = new BehaviorIngestPublisher(new InMemoryMqProvider(), objectMapper, metrics);
        BehaviorCollectService collectService = new BehaviorCollectService(publisher, metrics);
        MetaContext.setCurrentTenantId(42L);
        MetaContext.setCurrentUserId(7L);

        int accepted = collectService.record(List.of(event("metric-accepted-1", "page_view")));

        assertThat(accepted).isEqualTo(1);
        assertThat(registry.get("auraboot_behavior_ingest_accepted_total")
                .tag("path", "authenticated").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("auraboot_behavior_ingest_enqueued_total")
                .tag("topic", BehaviorIngestPublisher.TOPIC_EVENTS).counter().count()).isEqualTo(1.0);
    }

    @Test
    void persisterAndQuarantineConsumerRecordPersistedAndQuarantinedMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BehaviorIngestMetrics metrics = new BehaviorIngestMetrics(registry);
        InMemoryMqProvider mq = new InMemoryMqProvider();
        BehaviorIngestPublisher publisher = new BehaviorIngestPublisher(mq, objectMapper, metrics);
        BehaviorEventMapper eventMapper = mock(BehaviorEventMapper.class);
        BehaviorQuarantineMapper quarantineMapper = mock(BehaviorQuarantineMapper.class);
        BehaviorEventPersister persister = new BehaviorEventPersister(eventMapper, publisher, objectMapper, metrics);
        BehaviorQuarantineConsumer quarantineConsumer =
                new BehaviorQuarantineConsumer(mq, quarantineMapper, objectMapper, metrics);
        quarantineConsumer.subscribe();

        when(eventMapper.insert(any(BehaviorEvent.class))).thenReturn(1);
        assertThat(persister.persistOne(42L, 7L, event("metric-inserted-1", "page_view"))).isTrue();
        when(eventMapper.insert(any(BehaviorEvent.class))).thenThrow(new DuplicateKeyException("duplicate"));
        assertThat(persister.persistOne(42L, 7L, event("metric-duplicate-1", "page_view"))).isTrue();
        assertThat(persister.persistOne(42L, 7L, event(null, "page_view"))).isFalse();

        ArgumentCaptor<BehaviorQuarantine> quarantineCaptor = ArgumentCaptor.forClass(BehaviorQuarantine.class);
        verify(quarantineMapper).insert(quarantineCaptor.capture());
        assertThat(quarantineCaptor.getValue().getReason()).isEqualTo("malformed_missing_event_id");
        assertThat(registry.get("auraboot_behavior_ingest_persisted_total")
                .tag("outcome", "inserted").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("auraboot_behavior_ingest_persisted_total")
                .tag("outcome", "duplicate").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("auraboot_behavior_ingest_quarantined_total")
                .tag("reason", "malformed_missing_event_id").counter().count()).isEqualTo(1.0);
    }

    private BehaviorEventInput event(String id, String name) {
        BehaviorEventInput input = new BehaviorEventInput();
        input.setEventId(id);
        input.setEventName(name);
        return input;
    }
}
