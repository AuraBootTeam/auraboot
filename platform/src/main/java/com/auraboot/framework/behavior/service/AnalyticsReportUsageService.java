package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.mapper.BehaviorOutcomeOutboxMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Records generated exports using immutable save provenance and the executed query snapshot. */
@Service
@RequiredArgsConstructor
public class AnalyticsReportUsageService {
    private final BehaviorOutcomeOutboxMapper origins;
    private final AnalyticsJourneyService journey;

    public void exported(String reportPid, JsonNode executedDsl, UUID usageId, String format) {
        var origin = origins.findReportOrigin(MetaContext.getCurrentTenantId(), reportPid);
        if (origin == null || origin.get("analysisId") == null || origin.get("queryHash") == null) return;
        JsonNode sources = executedDsl.path("dataSources");
        String currentHash = "changed";
        if (sources.isObject() && sources.size() == 1) {
            JsonNode source = sources.elements().next();
            if ("aggregate".equals(source.path("type").asText()) && source.path("aggregateQuery").isObject()) {
                currentHash = AnalyticsQueryFingerprint.of(source.path("aggregateQuery"));
            }
        }
        journey.reportExported(origin.get("analysisId"), reportPid,
                usageId == null ? UUID.randomUUID() : usageId, currentHash,
                origin.get("queryHash").equals(currentHash), format);
    }
}
