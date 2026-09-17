package com.auraboot.framework.behavior.controller;

import com.auraboot.framework.behavior.service.AnalyticsResultViewService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/analytics/results")
@RequiredArgsConstructor
public class AnalyticsResultViewController {
    private final AnalyticsResultViewService views;

    @PostMapping("/{analysisId}/view")
    @AuthenticatedAccess("Records presentation only for a successful result owned by the current tenant and user")
    public ApiResponse<Void> viewed(@PathVariable UUID analysisId) {
        views.record(analysisId);
        return ApiResponse.success(null);
    }
}
