package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.InvariantDefinitionCreateRequest;
import com.auraboot.framework.meta.entity.DecisionAlarm;
import com.auraboot.framework.meta.entity.InvariantDefinition;
import com.auraboot.framework.meta.entity.InvariantEvaluationLog;
import com.auraboot.framework.meta.mapper.DecisionAlarmMapper;
import com.auraboot.framework.meta.mapper.InvariantEvaluationLogMapper;
import com.auraboot.framework.meta.service.InvariantDefinitionService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Invariant Controller.
 * CRUD, publish, and monitoring APIs for invariant definitions.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/invariants")
@RequiredArgsConstructor
public class InvariantController {

    private final InvariantDefinitionService invariantService;
    private final InvariantEvaluationLogMapper evaluationLogMapper;
    private final DecisionAlarmMapper alarmMapper;

    /**
     * Create a new invariant definition.
     */
    @PostMapping
    @RequirePermission(MetaPermission.INVARIANT_MANAGE)
    public ApiResponse<InvariantDefinition> create(@Valid @RequestBody InvariantDefinitionCreateRequest request) {
        InvariantDefinition definition = invariantService.create(request);
        return ApiResponse.success(definition);
    }

    /**
     * Get invariant by pid.
     */
    @GetMapping("/{pid}")
    @RequirePermission(MetaPermission.INVARIANT_READ)
    public ApiResponse<InvariantDefinition> getByPid(@PathVariable String pid) {
        InvariantDefinition definition = invariantService.getByPid(pid);
        return ApiResponse.success(definition);
    }

    /**
     * Get current invariant by code.
     */
    @GetMapping("/code/{code}")
    @RequirePermission(MetaPermission.INVARIANT_READ)
    public ApiResponse<InvariantDefinition> getByCode(@PathVariable String code) {
        InvariantDefinition definition = invariantService.getCurrentByCode(code);
        return ApiResponse.success(definition);
    }

    /**
     * List invariants by model code.
     */
    @GetMapping("/model/{modelCode}")
    @RequirePermission(MetaPermission.INVARIANT_READ)
    public ApiResponse<List<InvariantDefinition>> listByModelCode(@PathVariable String modelCode) {
        List<InvariantDefinition> definitions = invariantService.listByModelCode(modelCode);
        return ApiResponse.success(definitions);
    }

    /**
     * Update invariant definition (DRAFT only).
     */
    @PutMapping("/{pid}")
    @RequirePermission(MetaPermission.INVARIANT_MANAGE)
    public ApiResponse<InvariantDefinition> update(@PathVariable String pid,
                                                    @Valid @RequestBody InvariantDefinitionCreateRequest request) {
        InvariantDefinition definition = invariantService.update(pid, request);
        return ApiResponse.success(definition);
    }

    /**
     * Publish invariant definition.
     */
    @PostMapping("/{pid}/publish")
    @RequirePermission(MetaPermission.INVARIANT_MANAGE)
    public ApiResponse<Void> publish(@PathVariable String pid) {
        invariantService.publish(pid);
        return ApiResponse.success(null);
    }

    /**
     * Delete invariant definition (soft delete).
     */
    @DeleteMapping("/{pid}")
    @RequirePermission(MetaPermission.INVARIANT_MANAGE)
    public ApiResponse<Void> delete(@PathVariable String pid) {
        invariantService.delete(pid);
        return ApiResponse.success(null);
    }

    // ==================== Monitoring Endpoints ====================

    /**
     * Get violation statistics (grouped by invariant_code).
     */
    @GetMapping("/monitoring/stats")
    @RequirePermission(MetaPermission.INVARIANT_READ)
    public ApiResponse<List<Map<String, Object>>> getViolationStats(
            @RequestParam(defaultValue = "24") int hoursBack) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Instant since = Instant.now().minus(hoursBack, ChronoUnit.HOURS);
        List<Map<String, Object>> stats = evaluationLogMapper.getViolationStats(tenantId, since);
        return ApiResponse.success(stats);
    }

    /**
     * Get violation trend for a specific invariant (by hour).
     */
    @GetMapping("/monitoring/trend/{code}")
    @RequirePermission(MetaPermission.INVARIANT_READ)
    public ApiResponse<List<Map<String, Object>>> getViolationTrend(
            @PathVariable String code,
            @RequestParam(defaultValue = "24") int hoursBack) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Instant since = Instant.now().minus(hoursBack, ChronoUnit.HOURS);
        List<Map<String, Object>> trend = evaluationLogMapper.getViolationTrend(tenantId, code, since);
        return ApiResponse.success(trend);
    }

    /**
     * Get recent violations.
     */
    @GetMapping("/monitoring/recent-violations")
    @RequirePermission(MetaPermission.INVARIANT_READ)
    public ApiResponse<List<InvariantEvaluationLog>> getRecentViolations(
            @RequestParam(defaultValue = "50") int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<InvariantEvaluationLog> violations = evaluationLogMapper.findRecentViolations(tenantId, limit);
        return ApiResponse.success(violations);
    }

    /**
     * Get open invariant-violation alarms.
     */
    @GetMapping("/monitoring/alarms")
    @RequirePermission(MetaPermission.INVARIANT_READ)
    public ApiResponse<List<DecisionAlarm>> getInvariantAlarms(
            @RequestParam(defaultValue = "50") int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<DecisionAlarm> alarms = alarmMapper.findOpenAlarmsByType(tenantId, "invariant_violation", limit);
        return ApiResponse.success(alarms);
    }
}
