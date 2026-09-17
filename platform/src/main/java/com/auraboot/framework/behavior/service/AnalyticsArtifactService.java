package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.behavior.outcome.BehaviorOutcomeEvent;
import com.auraboot.framework.behavior.outcome.BehaviorOutcomePublisher;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

/** Validates server query provenance and records committed artifact outcomes. */
@Service
@RequiredArgsConstructor
public class AnalyticsArtifactService {
    private final BehaviorEventMapper events;
    private final BehaviorOutcomePublisher outcomes;

    public String verifyQuery(String analysisId, JsonNode widgets) {
        if (widgets == null || !widgets.isArray() || widgets.size() != 1) {
            throw new BusinessException(ResponseCode.BadParam, "An analytics dashboard requires one query widget");
        }
        return verifySource(analysisId, widgets.get(0).path("config").path("dataSource"));
    }

    public String verifyReportQuery(String analysisId, JsonNode dsl) {
        JsonNode sources = dsl == null ? null : dsl.get("dataSources");
        if (sources == null || !sources.isObject() || sources.size() != 1) {
            throw new BusinessException(ResponseCode.BadParam, "An analytics report requires one aggregate data source");
        }
        JsonNode source = sources.elements().next();
        if (!"aggregate".equals(source.path("type").asText())) {
            throw new BusinessException(ResponseCode.BadParam, "An analytics report requires an aggregate data source");
        }
        return verifySource(analysisId, source.path("aggregateQuery"));
    }

    private String verifySource(String analysisId, JsonNode source) {
        String expected = events.findSuccessfulQueryHash(MetaContext.getCurrentTenantId(),
                MetaContext.getCurrentUserId(), analysisId);
        if (expected == null) {
            throw new BusinessException(ResponseCode.BadParam, "Analysis is unavailable or its result has not been recorded");
        }
        if (!source.isObject() || !expected.equals(AnalyticsQueryFingerprint.of(source))) {
            throw new BusinessException(ResponseCode.BadParam, "Saved query differs from the analysis result");
        }
        return expected;
    }

    public void reportSaved(String analysisId, String reportPid, String queryHash) {
        outcomes.publish(BehaviorOutcomeEvent.builder()
                .tenantId(MetaContext.getCurrentTenantId()).userId(MetaContext.getCurrentUserId())
                .eventId(UUID.randomUUID().toString()).eventName("analytics_report_saved")
                .interactionId(analysisId).targetType("report").targetKey(reportPid)
                .props(Map.of("queryHash", queryHash)).build());
    }

    public void dashboardSaved(String analysisId, String dashboardPid, String queryHash) {
        outcomes.publish(BehaviorOutcomeEvent.builder()
                .tenantId(MetaContext.getCurrentTenantId()).userId(MetaContext.getCurrentUserId())
                .eventId(UUID.randomUUID().toString()).eventName("analytics_dashboard_saved")
                .interactionId(analysisId).targetType("dashboard").targetKey(dashboardPid)
                .props(Map.of("queryHash", queryHash)).build());
    }
}
