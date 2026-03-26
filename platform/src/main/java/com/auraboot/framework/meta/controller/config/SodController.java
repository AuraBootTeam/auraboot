package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.SodCheckResult;
import com.auraboot.framework.meta.dto.SodRuleCreateRequest;
import com.auraboot.framework.meta.dto.SodRuleUpdateRequest;
import com.auraboot.framework.meta.entity.SodRule;
import com.auraboot.framework.meta.entity.SodViolationLog;
import com.auraboot.framework.meta.service.impl.SodService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Separation of Duties (SoD) framework.
 * Provides CRUD for SoD rules, violation queries, and manual SoD checks.
 *
 * @author AuraBoot Team
 * @since 6.2.0
 */
@RestController
@RequestMapping("/api/sod")
@RequiredArgsConstructor
public class SodController {

    private final SodService sodService;

    // ==================== Rule CRUD ====================

    /**
     * List all SoD rules for the current tenant.
     * GET /api/sod/rules
     */
    @GetMapping("/rules")
    @RequirePermission(MetaPermission.META_SOD_READ)
    public ApiResponse<List<SodRule>> listRules() {
        return ApiResponse.success(sodService.listRules());
    }

    /**
     * Get a single SoD rule by ID.
     * GET /api/sod/rules/{id}
     */
    @GetMapping("/rules/{id}")
    @RequirePermission(MetaPermission.META_SOD_READ)
    public ApiResponse<SodRule> getRule(@PathVariable Long id) {
        return ApiResponse.success(sodService.getRule(id));
    }

    /**
     * Create a new SoD rule.
     * POST /api/sod/rules
     */
    @PostMapping("/rules")
    @RequirePermission(MetaPermission.META_SOD_MANAGE)
    public ApiResponse<SodRule> createRule(@RequestBody SodRuleCreateRequest request) {
        return ApiResponse.success(sodService.createRule(request));
    }

    /**
     * Update an existing SoD rule.
     * PUT /api/sod/rules/{id}
     */
    @PutMapping("/rules/{id}")
    @RequirePermission(MetaPermission.META_SOD_MANAGE)
    public ApiResponse<SodRule> updateRule(@PathVariable Long id, @RequestBody SodRuleUpdateRequest request) {
        return ApiResponse.success(sodService.updateRule(id, request));
    }

    /**
     * Delete (soft) an SoD rule.
     * DELETE /api/sod/rules/{id}
     */
    @DeleteMapping("/rules/{id}")
    @RequirePermission(MetaPermission.META_SOD_MANAGE)
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        sodService.deleteRule(id);
        return ApiResponse.success(null);
    }

    // ==================== Violations ====================

    /**
     * List SoD violations within a time range.
     * GET /api/sod/violations?start=2026-01-01T00:00:00&end=2026-12-31T23:59:59
     */
    @GetMapping("/violations")
    @RequirePermission(MetaPermission.META_SOD_READ)
    public ApiResponse<List<SodViolationLog>> getViolations(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        Instant startInstant = start.toInstant(ZoneOffset.UTC);
        Instant endInstant = end.toInstant(ZoneOffset.UTC);
        return ApiResponse.success(sodService.getViolations(startInstant, endInstant));
    }

    /**
     * List SoD violations by actor.
     * GET /api/sod/violations/by-actor?actorId=123
     */
    @GetMapping("/violations/by-actor")
    @RequirePermission(MetaPermission.META_SOD_READ)
    public ApiResponse<List<SodViolationLog>> getViolationsByActor(@RequestParam Long actorId) {
        return ApiResponse.success(sodService.getViolationsByActor(actorId));
    }

    /**
     * Override a recorded violation (e.g. manager approval to bypass SoD).
     * POST /api/sod/violations/{id}/override
     */
    @PostMapping("/violations/{id}/override")
    @RequirePermission(MetaPermission.META_SOD_MANAGE)
    public ApiResponse<SodViolationLog> overrideViolation(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Long overrideBy = MetaContext.getCurrentUserId();
        String reason = body.getOrDefault("reason", "");
        return ApiResponse.success(sodService.overrideViolation(id, overrideBy, reason));
    }

    // ==================== Manual Check ====================

    /**
     * Perform a manual SoD check (for testing and validation).
     * POST /api/sod/check
     * Body: { "commandCode": "pe:approve_purchase_order", "actorId": 123, "entityType": "pe_purchase_order", "entityId": 456 }
     */
    @PostMapping("/check")
    @RequirePermission(MetaPermission.META_SOD_READ)
    public ApiResponse<SodCheckResult> manualCheck(@RequestBody Map<String, Object> body) {
        String commandCode = (String) body.get("commandCode");
        Long actorId = body.get("actorId") != null ? Long.valueOf(body.get("actorId").toString()) : MetaContext.getCurrentUserId();
        String actorName = (String) body.getOrDefault("actorName", MetaContext.getCurrentUsername());
        String entityType = (String) body.get("entityType");
        Long entityId = body.get("entityId") != null ? Long.valueOf(body.get("entityId").toString()) : null;

        try {
            SodCheckResult result = sodService.checkSod(commandCode, actorId, actorName, entityType, entityId);
            return ApiResponse.success(result);
        } catch (com.auraboot.framework.exception.SodViolationException e) {
            // For manual check, return the result instead of throwing
            return ApiResponse.success(e.getCheckResult());
        }
    }
}
