package com.auraboot.framework.behavior.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.AnalyticsFunnel;
import com.auraboot.framework.behavior.dto.BehaviorQueryWindow;
import com.auraboot.framework.behavior.service.AnalyticsFunnelService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/analytics/behavior")
@RequiredArgsConstructor
public class AnalyticsFunnelController {
    private final AnalyticsFunnelService funnel;

    @GetMapping("/analysis-funnel")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<AnalyticsFunnel> query(@RequestParam(required = false) Instant from,
                                             @RequestParam(required = false) Instant to) {
        Instant cutoff = Instant.now();
        return ApiResponse.success(funnel.query(MetaContext.getCurrentTenantId(),
                BehaviorQueryWindow.resolve(from, to, cutoff), cutoff));
    }
}
