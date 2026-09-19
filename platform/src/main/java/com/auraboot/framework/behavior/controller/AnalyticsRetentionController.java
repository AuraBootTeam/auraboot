package com.auraboot.framework.behavior.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.*;
import com.auraboot.framework.behavior.service.AnalyticsRetentionService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/analytics/behavior")
@RequiredArgsConstructor
public class AnalyticsRetentionController {
    private final AnalyticsRetentionService retention;

    @GetMapping("/retention")
    @RequirePermission(MetaPermission.DASHBOARD_READ)
    public ApiResponse<AnalyticsRetention> query(@RequestParam(defaultValue = "user") String unit,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Instant cutoff = Instant.now();
        return ApiResponse.success(retention.query(MetaContext.getCurrentTenantId(), unit,
                BehaviorQueryWindow.resolve(from, to, cutoff), cutoff));
    }
}
