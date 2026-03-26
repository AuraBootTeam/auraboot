package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.AuditChainVerificationResult;
import com.auraboot.framework.meta.dto.AuditComplianceReport;
import com.auraboot.framework.meta.entity.AuditTrail;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * REST controller for querying and verifying the tamper-proof audit trail.
 *
 * @since 6.1.0
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditTrailController {

    private final AuditTrailService auditTrailService;

    /**
     * Get audit trail for a specific entity.
     * GET /api/audit/trail?entityType=pe_sales_order&entityId=123
     */
    @GetMapping("/trail")
    @RequirePermission(MetaPermission.META_AUDIT_TRAIL_READ)
    public ApiResponse<List<AuditTrail>> getAuditTrail(
            @RequestParam String entityType,
            @RequestParam Long entityId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<AuditTrail> trail = auditTrailService.getAuditTrail(tenantId, entityType, entityId);
        return ApiResponse.success(trail);
    }

    /**
     * Get audit records by actor within a time range.
     * GET /api/audit/by-actor?actorId=1&start=2026-01-01T00:00:00&end=2026-12-31T23:59:59
     */
    @GetMapping("/by-actor")
    @RequirePermission(MetaPermission.META_AUDIT_TRAIL_READ)
    public ApiResponse<List<AuditTrail>> getByActor(
            @RequestParam Long actorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Instant startInstant = start.toInstant(ZoneOffset.UTC);
        Instant endInstant = end.toInstant(ZoneOffset.UTC);
        List<AuditTrail> trail = auditTrailService.getAuditByActor(tenantId, actorId, startInstant, endInstant);
        return ApiResponse.success(trail);
    }

    /**
     * Get audit records by command code.
     * GET /api/audit/by-command?commandCode=pe:create_order
     */
    @GetMapping("/by-command")
    @RequirePermission(MetaPermission.META_AUDIT_TRAIL_READ)
    public ApiResponse<List<AuditTrail>> getByCommand(@RequestParam String commandCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<AuditTrail> trail = auditTrailService.getAuditByCommand(tenantId, commandCode);
        return ApiResponse.success(trail);
    }

    /**
     * Verify hash chain integrity for a sequence range.
     * POST /api/audit/verify?fromSeq=1&toSeq=1000
     */
    @PostMapping("/verify")
    @RequirePermission(MetaPermission.META_AUDIT_TRAIL_ADMIN)
    public ApiResponse<AuditChainVerificationResult> verifyChain(
            @RequestParam(defaultValue = "1") Long fromSeq,
            @RequestParam(required = false) Long toSeq) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Default toSeq to the latest sequence number
        if (toSeq == null) {
            AuditTrail latest = auditTrailService.getLatestRecord(tenantId);
            toSeq = (latest != null) ? latest.getSequenceNo() : 0L;
        }

        if (toSeq == 0L) {
            return ApiResponse.success(AuditChainVerificationResult.ok(0));
        }

        AuditChainVerificationResult result = auditTrailService.verifyChainIntegrity(tenantId, fromSeq, toSeq);
        return ApiResponse.success(result);
    }

    /**
     * Generate a compliance report for a time period.
     * GET /api/audit/report?start=2026-01-01T00:00:00&end=2026-12-31T23:59:59
     */
    @GetMapping("/report")
    @RequirePermission(MetaPermission.META_AUDIT_TRAIL_ADMIN)
    public ApiResponse<AuditComplianceReport> getComplianceReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Instant startInstant = start.toInstant(ZoneOffset.UTC);
        Instant endInstant = end.toInstant(ZoneOffset.UTC);
        AuditComplianceReport report = auditTrailService.generateComplianceReport(tenantId, startInstant, endInstant);
        return ApiResponse.success(report);
    }
}
