package com.auraboot.framework.governance.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.governance.dto.*;
import com.auraboot.framework.governance.service.MasterDataGovernanceService;
import com.auraboot.framework.governance.service.MasterDataPolicyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for master data governance operations.
 * Provides endpoints for change request management, version history, policies, and statistics.
 */
@Slf4j
@RestController
@RequestMapping("/api/governance")
public class GovernanceController {

    @Autowired
    private MasterDataGovernanceService governanceService;

    @Autowired
    private MasterDataPolicyService policyService;

    // ==================== Change Requests ====================

    /**
     * Create a new change request (initially in DRAFT status).
     */
    @PostMapping("/change-requests")
    @RequirePermission("governance.governance.submit")
    public ApiResponse<ChangeRequestResponse> submitChangeRequest(@RequestBody ChangeRequestCreateDTO dto) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userPid = MetaContext.getCurrentUserPid();
        ChangeRequestResponse result = governanceService.submitChangeRequest(dto, tenantId, userPid);
        return ApiResponse.success(result);
    }

    /**
     * Submit a DRAFT change request for review (DRAFT -> PENDING).
     */
    @PostMapping("/change-requests/{pid}/submit")
    @RequirePermission("governance.governance.submit")
    public ApiResponse<ChangeRequestResponse> submitForReview(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userPid = MetaContext.getCurrentUserPid();
        ChangeRequestResponse result = governanceService.submitForReview(pid, tenantId, userPid);
        return ApiResponse.success(result);
    }

    /**
     * List change requests with optional status filter.
     */
    @GetMapping("/change-requests")
    @RequirePermission("governance.governance.read")
    public ApiResponse<Page<ChangeRequestResponse>> listChangeRequests(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Page<ChangeRequestResponse> result = governanceService.listChangeRequests(tenantId, status, pageNum, pageSize);
        return ApiResponse.success(result);
    }

    /**
     * Get a single change request by PID.
     */
    @GetMapping("/change-requests/{pid}")
    @RequirePermission("governance.governance.read")
    public ApiResponse<ChangeRequestResponse> getChangeRequest(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        ChangeRequestResponse result = governanceService.getChangeRequest(pid, tenantId);
        return ApiResponse.success(result);
    }

    /**
     * Review (approve or reject) a change request.
     */
    @PostMapping("/change-requests/{pid}/review")
    @RequirePermission("governance.governance.review")
    public ApiResponse<ChangeRequestResponse> reviewChangeRequest(
            @PathVariable String pid,
            @RequestBody ChangeRequestReviewDTO dto) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String reviewerPid = MetaContext.getCurrentUserPid();
        ChangeRequestResponse result = governanceService.reviewChangeRequest(pid, dto, tenantId, reviewerPid);
        return ApiResponse.success(result);
    }

    /**
     * Apply an approved change request (APPROVED -> APPLIED).
     */
    @PostMapping("/change-requests/{pid}/apply")
    @RequirePermission("governance.governance.review")
    public ApiResponse<ChangeRequestResponse> applyChange(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userPid = MetaContext.getCurrentUserPid();
        ChangeRequestResponse result = governanceService.applyChange(pid, tenantId, userPid);
        return ApiResponse.success(result);
    }

    /**
     * Cancel a change request (DRAFT or PENDING -> CANCELLED).
     */
    @PostMapping("/change-requests/{pid}/cancel")
    @RequirePermission("governance.governance.submit")
    public ApiResponse<ChangeRequestResponse> cancelChangeRequest(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userPid = MetaContext.getCurrentUserPid();
        ChangeRequestResponse result = governanceService.cancelChangeRequest(pid, tenantId, userPid);
        return ApiResponse.success(result);
    }

    // ==================== Version History ====================

    /**
     * List version history for a specific entity record.
     */
    @GetMapping("/versions")
    @RequirePermission("governance.governance.read")
    public ApiResponse<List<VersionResponse>> listVersions(
            @RequestParam String entityType,
            @RequestParam String entityPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<VersionResponse> result = governanceService.listVersions(entityType, entityPid, tenantId);
        return ApiResponse.success(result);
    }

    /**
     * Get a specific version snapshot by PID.
     */
    @GetMapping("/versions/{versionPid}")
    @RequirePermission("governance.governance.read")
    public ApiResponse<VersionResponse> getVersion(@PathVariable String versionPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        VersionResponse result = governanceService.getVersion(versionPid, tenantId);
        return ApiResponse.success(result);
    }

    /**
     * Compute field-level diff between two versions.
     */
    @GetMapping("/versions/diff")
    @RequirePermission("governance.governance.read")
    public ApiResponse<VersionDiffResponse> diffVersions(
            @RequestParam String entityType,
            @RequestParam String entityPid,
            @RequestParam int fromVersion,
            @RequestParam int toVersion) {
        Long tenantId = MetaContext.getCurrentTenantId();
        VersionDiffResponse result = governanceService.diffVersions(entityType, entityPid, fromVersion, toVersion, tenantId);
        return ApiResponse.success(result);
    }

    /**
     * Create an initial version snapshot for an entity.
     */
    @PostMapping("/versions/init")
    @RequirePermission("governance.governance.review")
    public ApiResponse<VersionResponse> createInitialVersion(
            @RequestParam String entityType,
            @RequestParam String entityPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userPid = MetaContext.getCurrentUserPid();
        VersionResponse result = governanceService.createInitialVersion(entityType, entityPid, tenantId, userPid);
        return ApiResponse.success(result);
    }

    // ==================== Policies ====================

    /**
     * List all governance policies for the current tenant.
     */
    @GetMapping("/policies")
    @RequirePermission("governance.governance.read")
    public ApiResponse<List<PolicyResponse>> listPolicies() {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<PolicyResponse> result = policyService.listPolicies(tenantId);
        return ApiResponse.success(result);
    }

    /**
     * Get policy for a specific model.
     */
    @GetMapping("/policies/{modelCode}")
    @RequirePermission("governance.governance.read")
    public ApiResponse<PolicyResponse> getPolicy(@PathVariable String modelCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        PolicyResponse result = policyService.getPolicy(modelCode, tenantId);
        return ApiResponse.success(result);
    }

    /**
     * Create or update a governance policy (upsert).
     */
    @PostMapping("/policies")
    @RequirePermission("governance.governance.review")
    public ApiResponse<PolicyResponse> upsertPolicy(@RequestBody PolicyCreateDTO dto) {
        Long tenantId = MetaContext.getCurrentTenantId();
        PolicyResponse result = policyService.upsertPolicy(dto, tenantId);
        return ApiResponse.success(result);
    }

    /**
     * Delete a governance policy by PID.
     */
    @DeleteMapping("/policies/{pid}")
    @RequirePermission("governance.governance.review")
    public ApiResponse<Void> deletePolicy(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        policyService.deletePolicy(pid, tenantId);
        return ApiResponse.success(null);
    }

    // ==================== Statistics ====================

    /**
     * Get governance dashboard statistics.
     */
    @GetMapping("/stats")
    @RequirePermission("governance.governance.read")
    public ApiResponse<GovernanceStatsResponse> getStats() {
        Long tenantId = MetaContext.getCurrentTenantId();
        GovernanceStatsResponse result = governanceService.getStats(tenantId);
        return ApiResponse.success(result);
    }
}
