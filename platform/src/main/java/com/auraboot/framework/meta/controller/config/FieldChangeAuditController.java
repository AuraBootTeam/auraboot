package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.entity.FieldAuditConfig;
import com.auraboot.framework.meta.entity.FieldChangeLog;
import com.auraboot.framework.meta.service.impl.FieldChangeAuditService;
import com.auraboot.framework.meta.service.impl.FieldChangeAuditService.FieldAuditConfigRequest;
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
 * REST controller for querying field-level change audit logs
 * and managing field audit configuration.
 *
 * @since 6.2.0
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class FieldChangeAuditController {

    private final FieldChangeAuditService fieldChangeAuditService;

    // =====================================================================
    // Query endpoints
    // =====================================================================

    /**
     * Get all field changes for a specific record.
     * GET /api/audit/field-changes?modelCode=pe_sales_order&recordId=123
     */
    @GetMapping("/field-changes")
    @RequirePermission(MetaPermission.META_FIELD_AUDIT_READ)
    public ApiResponse<List<FieldChangeLog>> getRecordChanges(
            @RequestParam String modelCode,
            @RequestParam Long recordId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<FieldChangeLog> changes = fieldChangeAuditService.getRecordHistory(
                tenantId, modelCode, recordId);
        return ApiResponse.success(changes);
    }

    /**
     * Get change history for a specific field on a record.
     * GET /api/audit/field-changes/field?modelCode=pe_sales_order&recordId=123&fieldCode=pe_so_status
     */
    @GetMapping("/field-changes/field")
    @RequirePermission(MetaPermission.META_FIELD_AUDIT_READ)
    public ApiResponse<List<FieldChangeLog>> getFieldHistory(
            @RequestParam String modelCode,
            @RequestParam Long recordId,
            @RequestParam String fieldCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<FieldChangeLog> changes = fieldChangeAuditService.getFieldHistory(
                tenantId, modelCode, recordId, fieldCode);
        return ApiResponse.success(changes);
    }

    /**
     * Get all field changes made by a specific actor within a time range.
     * GET /api/audit/field-changes/actor?actorId=1&start=2026-01-01T00:00:00&end=2026-12-31T23:59:59
     */
    @GetMapping("/field-changes/actor")
    @RequirePermission(MetaPermission.META_FIELD_AUDIT_READ)
    public ApiResponse<List<FieldChangeLog>> getChangesByActor(
            @RequestParam Long actorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Instant startInstant = start.toInstant(ZoneOffset.UTC);
        Instant endInstant = end.toInstant(ZoneOffset.UTC);
        List<FieldChangeLog> changes = fieldChangeAuditService.getChangesByActor(
                tenantId, actorId, startInstant, endInstant);
        return ApiResponse.success(changes);
    }

    /**
     * Get a change report for a model within a time range.
     * GET /api/audit/field-changes/report?modelCode=pe_sales_order&start=2026-01-01T00:00:00&end=2026-12-31T23:59:59
     */
    @GetMapping("/field-changes/report")
    @RequirePermission(MetaPermission.META_FIELD_AUDIT_READ)
    public ApiResponse<Map<String, Object>> getChangeReport(
            @RequestParam String modelCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Instant startInstant = start.toInstant(ZoneOffset.UTC);
        Instant endInstant = end.toInstant(ZoneOffset.UTC);
        Map<String, Object> report = fieldChangeAuditService.getChangeReport(
                tenantId, modelCode, startInstant, endInstant);
        return ApiResponse.success(report);
    }

    // =====================================================================
    // Config endpoints
    // =====================================================================

    /**
     * Get audit configuration for a model.
     * GET /api/audit/field-config?modelCode=pe_sales_order
     */
    @GetMapping("/field-config")
    @RequirePermission(MetaPermission.META_FIELD_AUDIT_READ)
    public ApiResponse<List<FieldAuditConfig>> getAuditConfig(
            @RequestParam String modelCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<FieldAuditConfig> configs = fieldChangeAuditService.getAuditConfig(tenantId, modelCode);
        return ApiResponse.success(configs);
    }

    /**
     * Configure field auditing for a single field.
     * POST /api/audit/field-config
     * Body: { "modelCode": "pe_sales_order", "fieldCode": "pe_so_status", "enabled": true, "requireReason": false, "notifyOnChange": true }
     */
    @PostMapping("/field-config")
    @RequirePermission(MetaPermission.META_FIELD_AUDIT_MANAGE)
    public ApiResponse<FieldAuditConfig> configureFieldAudit(
            @RequestBody FieldAuditConfigRequestDTO request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        FieldAuditConfig result = fieldChangeAuditService.configureFieldAudit(
                tenantId, request.modelCode(), request.fieldCode(),
                request.enabled(), request.requireReason(), request.notifyOnChange());
        return ApiResponse.success(result);
    }

    /**
     * Bulk configure field auditing for multiple fields on a model.
     * POST /api/audit/field-config/bulk
     * Body: { "modelCode": "pe_sales_order", "configs": [{ "fieldCode": "pe_so_status", "enabled": true, ... }] }
     */
    @PostMapping("/field-config/bulk")
    @RequirePermission(MetaPermission.META_FIELD_AUDIT_MANAGE)
    public ApiResponse<List<FieldAuditConfig>> bulkConfigureFieldAudit(
            @RequestBody BulkFieldAuditConfigRequestDTO request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<FieldAuditConfigRequest> configs = request.configs().stream()
                .map(c -> new FieldAuditConfigRequest(
                        c.fieldCode(), c.enabled(), c.requireReason(), c.notifyOnChange()))
                .toList();
        List<FieldAuditConfig> results = fieldChangeAuditService.bulkConfigureFieldAudit(
                tenantId, request.modelCode(), configs);
        return ApiResponse.success(results);
    }

    // =====================================================================
    // Request DTOs (records)
    // =====================================================================

    public record FieldAuditConfigRequestDTO(
            String modelCode,
            String fieldCode,
            boolean enabled,
            boolean requireReason,
            boolean notifyOnChange
    ) {}

    public record BulkFieldAuditConfigRequestDTO(
            String modelCode,
            List<FieldConfigEntry> configs
    ) {}

    public record FieldConfigEntry(
            String fieldCode,
            boolean enabled,
            boolean requireReason,
            boolean notifyOnChange
    ) {}
}
