package com.auraboot.framework.behavior.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorAnalyticsRecords;
import com.auraboot.framework.behavior.dto.BehaviorDailyPoint;
import com.auraboot.framework.behavior.dto.BehaviorEventCount;
import com.auraboot.framework.behavior.dto.BehaviorOverview;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
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
public class BehaviorAnalyticsController {

    private final BehaviorEventMapper behaviorEventMapper;

    /** PV / UV / sessions / total events for the current tenant. */
    @GetMapping("/overview")
    public ApiResponse<BehaviorAnalyticsRecords<BehaviorOverview>> overview() {
        return ApiResponse.success(new BehaviorAnalyticsRecords<>(
                List.of(behaviorEventMapper.overview(MetaContext.getCurrentTenantId()))));
    }

    /** Top events by name for the current tenant. */
    @GetMapping("/top-events")
    public ApiResponse<BehaviorAnalyticsRecords<BehaviorEventCount>> topEvents() {
        return ApiResponse.success(new BehaviorAnalyticsRecords<>(
                behaviorEventMapper.topEvents(MetaContext.getCurrentTenantId())));
    }

    /** Daily PV/UV/total time series for the current tenant (dashboard trend). */
    @GetMapping("/daily")
    public List<BehaviorDailyPoint> daily() {
        return behaviorEventMapper.dailyTrend(MetaContext.getCurrentTenantId());
    }
}
