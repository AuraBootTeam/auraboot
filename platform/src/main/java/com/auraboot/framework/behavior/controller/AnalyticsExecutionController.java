package com.auraboot.framework.behavior.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.AnalyticsExecution;
import com.auraboot.framework.behavior.dto.BehaviorQueryWindow;
import com.auraboot.framework.behavior.service.AnalyticsExecutionService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/analytics/behavior")
@RequiredArgsConstructor
public class AnalyticsExecutionController {
    private final AnalyticsExecutionService executions;

    @GetMapping("/executions")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<AnalyticsExecution> query(@RequestParam(required = false) Instant from,
                                               @RequestParam(required = false) Instant to) {
        Instant cutoff = Instant.now();
        return ApiResponse.success(executions.query(MetaContext.getCurrentTenantId(),
                BehaviorQueryWindow.resolve(from, to, cutoff), cutoff));
    }
}
