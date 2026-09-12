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
        String expected = events.findSuccessfulQueryHash(MetaContext.getCurrentTenantId(),
                MetaContext.getCurrentUserId(), analysisId);
        if (expected == null) {
            throw new BusinessException(ResponseCode.BadParam, "Analysis is unavailable or its result has not been recorded");
        }
        JsonNode source = widgets.get(0).path("config").path("dataSource");
        if (!source.isObject() || !expected.equals(AnalyticsQueryFingerprint.of(source))) {
            throw new BusinessException(ResponseCode.BadParam, "Saved query differs from the analysis result");
        }
        return expected;
    }

    public void dashboardSaved(String analysisId, String dashboardPid, String queryHash) {
        outcomes.publish(BehaviorOutcomeEvent.builder()
                .tenantId(MetaContext.getCurrentTenantId()).userId(MetaContext.getCurrentUserId())
                .eventId(UUID.randomUUID().toString()).eventName("analytics_dashboard_saved")
                .interactionId(analysisId).targetType("dashboard").targetKey(dashboardPid)
                .props(Map.of("queryHash", queryHash)).build());
    }
}
