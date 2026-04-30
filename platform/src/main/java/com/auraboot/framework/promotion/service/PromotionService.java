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
}
