package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.impl.ReconciliationService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * Reconciliation Controller
 * RESTful API for automated matching of records between two data sources.
 * Supports supplier statement, bank transaction, and intercompany reconciliation.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@RestController
@RequestMapping("/api/reconciliation")
@RequiredArgsConstructor
@Validated
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    // ==================== Profile CRUD ====================

    @PostMapping("/profiles")
    @RequirePermission(MetaPermission.RECON_MANAGE)
    public ApiResponse<ReconciliationProfileDTO> createProfile(
            @Valid @RequestBody ReconciliationProfileRequest request) {
        ReconciliationProfileDTO result = reconciliationService.createProfile(request);
        return ApiResponse.success(result);
    }

    @PutMapping("/profiles/{id}")
    @RequirePermission(MetaPermission.RECON_MANAGE)
    public ApiResponse<ReconciliationProfileDTO> updateProfile(
            @PathVariable Long id,
            @Valid @RequestBody ReconciliationProfileRequest request) {
        ReconciliationProfileDTO result = reconciliationService.updateProfile(id, request);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/profiles/{id}")
    @RequirePermission(MetaPermission.RECON_MANAGE)
    public ApiResponse<Void> deleteProfile(@PathVariable Long id) {
        reconciliationService.deleteProfile(id);
        return ApiResponse.success();
    }

    @GetMapping("/profiles/{id}")
    @RequirePermission(MetaPermission.RECON_READ)
    public ApiResponse<ReconciliationProfileDTO> getProfile(@PathVariable Long id) {
        ReconciliationProfileDTO result = reconciliationService.getProfile(id);
        return ApiResponse.success(result);
    }

    @GetMapping("/profiles")
    @RequirePermission(MetaPermission.RECON_READ)
    public ApiResponse<List<ReconciliationProfileDTO>> listProfiles() {
        List<ReconciliationProfileDTO> result = reconciliationService.listProfiles();
        return ApiResponse.success(result);
    }

    // ==================== Run Management ====================

    @PostMapping("/run")
    @RequirePermission(MetaPermission.RECON_MANAGE)
    public ApiResponse<ReconciliationRunDTO> startReconciliation(
            @Valid @RequestBody ReconciliationRunRequest request) {
        ReconciliationRunDTO result = reconciliationService.startReconciliation(request);
        return ApiResponse.success(result);
    }

    @GetMapping("/runs")
    @RequirePermission(MetaPermission.RECON_READ)
    public ApiResponse<PaginationResult<ReconciliationRunDTO>> listRuns(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        PaginationResult<ReconciliationRunDTO> result = reconciliationService.listRuns(pageNum, pageSize);
        return ApiResponse.success(result);
    }

    @GetMapping("/runs/{runCode}")
    @RequirePermission(MetaPermission.RECON_READ)
    public ApiResponse<ReconciliationRunDTO> getRunSummary(@PathVariable String runCode) {
        ReconciliationRunDTO result = reconciliationService.getRunSummary(runCode);
        return ApiResponse.success(result);
    }

    @GetMapping("/runs/{runCode}/items")
    @RequirePermission(MetaPermission.RECON_READ)
    public ApiResponse<PaginationResult<ReconciliationItemDTO>> getRunItems(
            @PathVariable String runCode,
            @RequestParam(required = false) String matchStatus,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        PaginationResult<ReconciliationItemDTO> result = reconciliationService.getRunItems(
                runCode, matchStatus, pageNum, pageSize);
        return ApiResponse.success(result);
    }

    @GetMapping("/runs/{runCode}/report")
    @RequirePermission(MetaPermission.RECON_READ)
    public ApiResponse<ReconciliationReportDTO> getReconciliationReport(@PathVariable String runCode) {
        ReconciliationReportDTO result = reconciliationService.getReconciliationReport(runCode);
        return ApiResponse.success(result);
    }

    // ==================== Item Resolution ====================

    @PostMapping("/items/{id}/resolve")
    @RequirePermission(MetaPermission.RECON_MANAGE)
    public ApiResponse<ReconciliationItemDTO> resolveItem(
            @PathVariable Long id,
            @Valid @RequestBody ReconciliationItemResolveRequest request) {
        ReconciliationItemDTO result = reconciliationService.resolveItem(id, request);
        return ApiResponse.success(result);
    }
}
