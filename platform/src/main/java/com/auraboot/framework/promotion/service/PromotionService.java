package com.auraboot.framework.promotion.service;

import com.auraboot.framework.promotion.dto.DryRunResult;
import com.auraboot.framework.promotion.dto.PromotionRequest;
import com.auraboot.framework.promotion.dto.PromotionResponse;

import java.util.List;

/**
 * PoC scope (task #7): create / list / get / validate.
 * Apply (task #9), reject (UX phase 2), missing-dep analysis (task #8) are deferred.
 */
public interface PromotionService {

    PromotionResponse create(PromotionRequest request, Long tenantId, Long userId);

    PromotionResponse getByPid(String pid, Long tenantId);

    List<PromotionResponse> listByStatus(Long tenantId, String statusFilter);

    /**
     * Run a dry-run against the promotion's units. Detects content conflicts where the target env
     * already holds a different version of the same resource (matched by page_key for PAGE_SCHEMA).
     * Persists DryRunResult and transitions DRAFT → VALIDATED if no errors.
     *
     * @throws IllegalStateException if current status is not DRAFT or VALIDATED
     */
    DryRunResult validate(String pid, Long tenantId);

    /**
     * Apply a VALIDATED promotion to its target env. Each unit either INSERTs a new PageSchema
     * row (target env has no matching page_key) or bumps the version of the existing target row
     * (marks old as not_current, INSERTs new is_current row).
     *
     * <p>Pre-conditions: status=VALIDATED, dry-run is fresh (≤24h) and last result was valid.
     * Four-eyes check: if target env is locked, approver must differ from creator AND reason
     * must be non-blank.
     *
     * <p>On failure mid-apply, the outer transaction rolls back all writes; a separate
     * REQUIRES_NEW transaction persists status=FAILED and failure_reason for audit/retry UX.
     *
     * @param pid         promotion business id
     * @param tenantId    current tenant
     * @param approverId  current user (the apply-er); used for four-eyes guard
     * @param reason      applied_reason for audit; required when target locked
     */
    PromotionResponse apply(String pid, Long tenantId, Long approverId, String reason);
}
