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

    public void resultViewed(String analysisId, String queryHash) {
        String identity = MetaContext.getCurrentTenantId() + ":" + MetaContext.getCurrentUserId()
                + ":result-viewed:" + analysisId;
        publish(analysisId, "analytics_result_viewed", Map.of("queryHash", queryHash, "signalSource", "client_visible"),
                UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
    }

    public void dashboardUsed(String analysisId, String dashboardPid, String widgetId,
                              UUID usageId, String queryHash, boolean originalQuery) {
        Instant occurredAt = Instant.now();
        String identity = MetaContext.getCurrentTenantId() + ":" + MetaContext.getCurrentUserId()
                + ":dashboard-used:" + dashboardPid + ":" + widgetId + ":" + usageId
                + ":" + occurredAt.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        publish(analysisId, "analytics_dashboard_used", Map.of("targetType", "dashboard",
                "targetKey", dashboardPid, "widgetId", widgetId, "queryHash", queryHash,
                "originalQuery", originalQuery),
                UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), occurredAt);
    }

    public void reportExported(String analysisId, String reportPid, UUID usageId,
                               String queryHash, boolean originalQuery, String format) {
        Instant occurredAt = Instant.now();
        String identity = MetaContext.getCurrentTenantId() + ":" + MetaContext.getCurrentUserId()
                + ":report-exported:" + reportPid + ":" + usageId + ":" + format
                + ":" + occurredAt.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        publish(analysisId, "analytics_report_used", Map.of("targetType", "report",
                "targetKey", reportPid, "queryHash", queryHash, "originalQuery", originalQuery,
                "usageKind", "export_generated", "format", format),
                UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), occurredAt);
    }

    private void publish(String analysisId, String name, Map<String, Object> props) {
        publish(analysisId, name, props, UUID.randomUUID().toString());
    }

    private void publish(String analysisId, String name, Map<String, Object> props, String eventId) {
        publish(analysisId, name, props, eventId, Instant.now());
    }

    private void publish(String analysisId, String name, Map<String, Object> props, String eventId, Instant occurredAt) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            throw new IllegalStateException("Analytics journey requires an authenticated tenant context");
        }
        BehaviorEventInput event = new BehaviorEventInput();
        event.setEventId(eventId);
        event.setSchemaVersion("1");
        event.setEventName(name);
        event.setEventCategory("analytics");
        event.setSource("server");
        event.setIdentityQuality("authenticated");
        event.setOccurredAt(occurredAt);
        event.setInteractionId(analysisId);
        event.setSamplingProbability(BigDecimal.ONE);
        event.setProducerName("aurabot-analytics");
        event.setProducerVersion("1");
        event.setProps(props);
        publisher.publish(tenantId, userId, List.of(event));
    }
}
