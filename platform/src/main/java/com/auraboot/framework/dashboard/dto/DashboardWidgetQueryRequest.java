package com.auraboot.framework.dashboard.dto;

import com.auraboot.framework.meta.dto.AggregateQueryRequest.FilterConfig;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Only additive interaction filters are accepted; the saved query is loaded on the server. */
public record DashboardWidgetQueryRequest(
        @NotNull UUID usageId,
        @Size(max = 100) List<FilterConfig> linkageFilters,
        @Size(max = 100) List<FilterConfig> drillFilters) {}
