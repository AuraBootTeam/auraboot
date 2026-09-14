package com.auraboot.framework.dashboard.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.dashboard.dto.DashboardWidgetQueryRequest;
import com.auraboot.framework.dashboard.service.DashboardWidgetQueryService;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboards")
@RequiredArgsConstructor
@Validated
public class DashboardWidgetQueryController {
    private final DashboardWidgetQueryService queries;

    @PostMapping("/{pid}/widgets/{widgetId}/data")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<AggregateQueryResponse> query(@PathVariable @NotBlank String pid,
            @PathVariable @NotBlank String widgetId, @Valid @RequestBody DashboardWidgetQueryRequest request) {
        return ApiResponse.success(queries.execute(pid, widgetId, request));
    }
}
