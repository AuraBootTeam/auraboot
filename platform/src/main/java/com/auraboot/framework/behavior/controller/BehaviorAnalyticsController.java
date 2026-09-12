package com.auraboot.framework.behavior.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorAnalyticsRecords;
import com.auraboot.framework.behavior.dto.BehaviorDailyPoint;
import com.auraboot.framework.behavior.dto.BehaviorEventCount;
import com.auraboot.framework.behavior.dto.BehaviorOverview;
import com.auraboot.framework.behavior.service.BehaviorAnalyticsService;
import com.auraboot.framework.behavior.dto.BehaviorQueryWindow;
import java.time.Instant;
import org.springframework.web.bind.annotation.RequestParam;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Behavior analytics read API (M1 analysis layer; SoT §5). Tenant-scoped from
 * {@link MetaContext} — auth-gated, no cross-tenant access (mirrors the A-G6
 * usage analytics + AiTraceController pattern).
 */
@RestController
@RequestMapping("/api/analytics/behavior")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.DASHBOARD_READ)
public class BehaviorAnalyticsController {

    private final BehaviorAnalyticsService analyticsService;

    /** PV / UV / sessions / total events for the current tenant. */
    @GetMapping("/overview")
    public ApiResponse<BehaviorAnalyticsRecords<BehaviorOverview>> overview(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return ApiResponse.success(new BehaviorAnalyticsRecords<>(
                List.of(analyticsService.overview(MetaContext.getCurrentTenantId(), BehaviorQueryWindow.resolve(from, to, Instant.now())))));
    }

    /** Top events by name for the current tenant. */
    @GetMapping("/top-events")
    public ApiResponse<BehaviorAnalyticsRecords<BehaviorEventCount>> topEvents(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return ApiResponse.success(new BehaviorAnalyticsRecords<>(
                analyticsService.topEvents(MetaContext.getCurrentTenantId(), BehaviorQueryWindow.resolve(from, to, Instant.now()))));
    }

    /** Daily PV/UV/total time series for the current tenant (dashboard trend). */
    @GetMapping("/daily")
    public List<BehaviorDailyPoint> daily(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return analyticsService.daily(MetaContext.getCurrentTenantId(), BehaviorQueryWindow.resolve(from, to, Instant.now()));
    }
}
