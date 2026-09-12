package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.ingest.BehaviorIngestPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Authoritative analytics query stages; never accepts client event envelopes. */
@Service
@RequiredArgsConstructor
public class AnalyticsJourneyService {
    private final BehaviorIngestPublisher publisher;

    public String requested() {
        String analysisId = UUID.randomUUID().toString();
        publish(analysisId, "analytics_requested", Map.of());
        return analysisId;
    }

    public void succeeded(String analysisId, int rowCount, com.fasterxml.jackson.databind.JsonNode query) {
        publish(analysisId, "analytics_query_succeeded", Map.of("rowCount", rowCount, "queryHash", AnalyticsQueryFingerprint.of(query)));
    }

    public void failed(String analysisId) {
        publish(analysisId, "analytics_query_failed", Map.of());
    }

    private void publish(String analysisId, String name, Map<String, Object> props) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            throw new IllegalStateException("Analytics journey requires an authenticated tenant context");
        }
        BehaviorEventInput event = new BehaviorEventInput();
        event.setEventId(UUID.randomUUID().toString());
        event.setSchemaVersion("1");
        event.setEventName(name);
        event.setEventCategory("analytics");
        event.setSource("server");
        event.setIdentityQuality("authenticated");
        event.setOccurredAt(Instant.now());
        event.setInteractionId(analysisId);
        event.setSamplingProbability(BigDecimal.ONE);
        event.setProducerName("aurabot-analytics");
        event.setProducerVersion("1");
        event.setProps(props);
        publisher.publish(tenantId, userId, List.of(event));
    }
}
