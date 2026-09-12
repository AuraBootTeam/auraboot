package com.auraboot.framework.dashboard.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.service.AnalyticsJourneyService;
import com.auraboot.framework.behavior.service.AnalyticsQueryFingerprint;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.dashboard.dto.DashboardWidgetQueryRequest;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.service.AggregateQueryService;
import com.auraboot.framework.meta.service.ChartRuntimeFilterResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Executes an accessible saved query before recording dashboard use. */
@Service
@RequiredArgsConstructor
public class DashboardWidgetQueryService {
    private final DashboardService dashboards;
    private final AggregateQueryService queries;
    private final ChartRuntimeFilterResolver runtimeFilters;
    private final ObjectMapper objectMapper;
    private final AnalyticsJourneyService journey;

    public AggregateQueryResponse execute(String pid, String widgetId, DashboardWidgetQueryRequest interaction) {
        var dashboard = dashboards.findByPid(pid);
        if (dashboard == null || !Objects.equals(dashboard.getTenantId(), MetaContext.getCurrentTenantId())) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Dashboard not found");
        }
        JsonNode widget = null;
        if (dashboard.getWidgets() != null && dashboard.getWidgets().isArray()) {
            for (JsonNode candidate : dashboard.getWidgets()) {
                if (widgetId.equals(candidate.path("id").asText())) {
                    if (widget != null) {
                        throw new BusinessException(ResponseCode.BadParam, "Dashboard has ambiguous widget identifiers");
                    }
                    widget = candidate;
                }
            }
        }
        if (widget == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Dashboard widget not found");
        }
        JsonNode source = widget.path("config").path("dataSource");
        if (!List.of("aggregate", "namedQuery").contains(source.path("type").asText())) {
            throw new BusinessException(ResponseCode.BadParam, "Widget does not have a saved aggregate query");
        }
        AggregateQueryRequest request = objectMapper.convertValue(source, AggregateQueryRequest.class);
        request.setFilters(append(request.getFilters(), interaction.linkageFilters()));
        request.setDrillFilters(append(request.getDrillFilters(), interaction.drillFilters()));
        runtimeFilters.resolve(request);
        AggregateQueryResponse result = queries.execute(request);
        JsonNode origin = dashboard.getExtension() == null ? null : dashboard.getExtension().get("analyticsOrigin");
        if (origin != null && origin.path("analysisId").isTextual()) {
            String savedHash = AnalyticsQueryFingerprint.of(source);
            journey.dashboardUsed(origin.path("analysisId").asText(), pid, widgetId, interaction.usageId(),
                    savedHash, savedHash.equals(origin.path("queryHash").asText()));
        }
        return result;
    }

    private static List<AggregateQueryRequest.FilterConfig> append(
            List<AggregateQueryRequest.FilterConfig> saved, List<AggregateQueryRequest.FilterConfig> additional) {
        var combined = new ArrayList<AggregateQueryRequest.FilterConfig>();
        if (saved != null) combined.addAll(saved);
        if (additional != null) combined.addAll(additional);
        return combined;
    }
}
