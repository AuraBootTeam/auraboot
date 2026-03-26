package com.auraboot.framework.governance.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.auraboot.framework.governance.dto.*;

import java.util.List;

/**
 * Service interface for master data governance operations:
 * change requests (submit/review) and version history.
 */
public interface MasterDataGovernanceService {

    // ---- Change Requests ----

    /**
     * Submit a new change request for review.
     */
    ChangeRequestResponse submitChangeRequest(ChangeRequestCreateDTO dto, Long tenantId, String submitterPid);

    /**
     * List change requests with pagination and optional status filter.
     */
    Page<ChangeRequestResponse> listChangeRequests(Long tenantId, String status, int pageNum, int pageSize);

    /**
     * Get a single change request by PID.
     */
    ChangeRequestResponse getChangeRequest(String pid, Long tenantId);

    /**
     * Submit a DRAFT change request for review.
     * Transitions DRAFT -> PENDING_REVIEW.
     */
    ChangeRequestResponse submitForReview(String pid, Long tenantId, String submitterPid);

    /**
     * Review (approve or reject) a change request.
     * Transitions PENDING_REVIEW -> APPROVED or PENDING_REVIEW -> REJECTED.
     */
    ChangeRequestResponse reviewChangeRequest(String pid, ChangeRequestReviewDTO dto, Long tenantId, String reviewerPid);

    /**
     * Apply an approved change request - actually executes the change.
     * Transitions APPROVED -> APPLIED and creates a version snapshot.
     */
    ChangeRequestResponse applyChange(String pid, Long tenantId, String applierPid);

    /**
     * Cancel a pending change request (by the submitter).
     */
    ChangeRequestResponse cancelChangeRequest(String pid, Long tenantId, String requesterPid);

    // ---- Version History ----

    /**
     * List version history for a specific entity record.
     */
    List<VersionResponse> listVersions(String entityType, String entityPid, Long tenantId);

    /**
     * Get a specific version snapshot.
     */
    VersionResponse getVersion(String versionPid, Long tenantId);

    /**
     * Compute field-level diff between two versions of the same entity.
     */
    VersionDiffResponse diffVersions(String entityType, String entityPid, int fromVersion, int toVersion, Long tenantId);

    /**
     * Create an initial version snapshot for an entity (used when governance is first enabled).
     */
    VersionResponse createInitialVersion(String entityType, String entityPid, Long tenantId, String creatorPid);

    // ---- Statistics ----

    /**
     * Get governance dashboard statistics for a tenant.
     */
    GovernanceStatsResponse getStats(Long tenantId);
}
