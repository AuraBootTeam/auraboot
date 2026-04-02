package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.service.ApsSchedulingService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * APS (Advanced Planning & Scheduling) controller for PCBA manufacturing.
 * Provides endpoints to run forward scheduling and clear results.
 */
@Slf4j
@RestController
@RequestMapping("/api/manufacturing/aps")
@RequiredArgsConstructor
public class ApsSchedulingController {

    private final ApsSchedulingService apsSchedulingService;

    /**
     * Run forward scheduling for the current tenant.
     *
     * @param horizon scheduling horizon in days (default 30)
     * @return scheduling summary with scheduled count and conflict count
     */
    @PostMapping("/schedule")
    public ApiResponse<Map<String, Object>> runSchedule(
            @RequestParam(value = "horizon", defaultValue = "30") int horizon) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            return ApiResponse.error("Tenant context not found");
        }

        if (horizon < 1 || horizon > 365) {
            return ApiResponse.error("Horizon must be between 1 and 365 days");
        }

        try {
            log.info("Running APS forward scheduling for tenant {}, horizon={} days", tenantId, horizon);
            Map<String, Object> result = apsSchedulingService.runSchedule(tenantId, horizon);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("APS scheduling failed for tenant {}: {}", tenantId, e.getMessage(), e);
            return ApiResponse.error("Scheduling failed: " + e.getMessage());
        }
    }

    /**
     * Clear all SCHEDULED results for the current tenant (to allow re-scheduling).
     *
     * @return cleared count
     */
    @PostMapping("/clear")
    public ApiResponse<Map<String, Object>> clearSchedule() {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            return ApiResponse.error("Tenant context not found");
        }

        try {
            log.info("Clearing APS schedule results for tenant {}", tenantId);
            Map<String, Object> result = apsSchedulingService.clearSchedule(tenantId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("APS clear failed for tenant {}: {}", tenantId, e.getMessage(), e);
            return ApiResponse.error("Clear failed: " + e.getMessage());
        }
    }
}
