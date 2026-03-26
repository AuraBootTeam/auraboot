package com.auraboot.framework.bi.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.dto.DashboardDataResponse;
import com.auraboot.framework.bi.service.DashboardDataService;
import com.auraboot.framework.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for batch-fetching dashboard widget data.
 * Primary consumer: Data Screen auto-refresh feature.
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboards")
@RequiredArgsConstructor
@Tag(name = "Dashboard Data", description = "Batch fetch dashboard widget data for Data Screen mode")
public class DashboardDataController {

    private final DashboardDataService dashboardDataService;

    @GetMapping("/{dashboardId}/data")
    @Operation(summary = "Fetch all widget data for a dashboard",
            description = "Returns all widget data in one call. Used by Data Screen auto-refresh.")
    public ApiResponse<DashboardDataResponse> getDashboardData(
            @PathVariable String dashboardId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Fetching dashboard data: dashboardId={}, forceRefresh={}, tenantId={}", dashboardId, forceRefresh, tenantId);

        DashboardDataResponse response = dashboardDataService.fetchDashboardData(dashboardId, forceRefresh, tenantId);
        return ApiResponse.success(response);
    }
}
