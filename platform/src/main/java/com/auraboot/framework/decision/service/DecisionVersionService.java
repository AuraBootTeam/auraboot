package com.auraboot.framework.decision.service;

import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.model.DecisionValidateResult;

import java.util.List;

/**
 * Decision version lifecycle service.
 *
 * <p>State machine: DRAFT → VALIDATED → PUBLISHED → DEPRECATED → RETIRED.<br>
 * PUBLISHED and beyond are immutable (no content edits).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface DecisionVersionService {

    /**
     * Create a new DRAFT version for an existing definition.
     * Version number = max(existing versions for that code) + 1.
     */
    DrtVersionDTO createDraft(String decisionCode, DrtVersionCreateRequest request);

    /**
     * Validate the draft: delegates to {@link com.auraboot.framework.decision.runtime.DecisionRuntime#validate}.
     * On success transitions status to VALIDATED and persists field_refs / function_refs.
     * On failure leaves status as DRAFT and returns the error list.
     *
     * @return the validate result (callers can inspect errors)
     */
    DecisionValidateResult validate(String pid);

    /**
     * Publish a VALIDATED version.
     * Requires: current status == VALIDATED (VersionStatus.canTransitionTo(PUBLISHED)).
     * Sets status PUBLISHED, published_by = current user, published_at = now.
     */
    DrtVersionDTO publish(String pid);

    /**
     * Publish with explicit blast-radius acknowledgement when downstream consumers exist.
     */
    DrtVersionDTO publish(String pid, boolean impactAcknowledged);

    /**
     * M7 governance — submit a VALIDATED version for 4-eyes approval (VALIDATED → PENDING_APPROVAL).
     * The author submits; a different user with {@code decision.approve} then approves/rejects.
     */
    DrtVersionDTO submitForApproval(String pid);

    /**
     * Approve a PENDING_APPROVAL version and publish it (PENDING_APPROVAL → PUBLISHED). Records the
     * approver (approval_by/at) and the publisher (published_by/at). Requires {@code decision.approve}.
     */
    DrtVersionDTO approve(String pid, String note);

    /**
     * Approve + publish with explicit blast-radius acknowledgement when downstream consumers exist.
     */
    DrtVersionDTO approve(String pid, String note, boolean impactAcknowledged);

    /** Reject a PENDING_APPROVAL version (PENDING_APPROVAL → REJECTED), recording the reason. */
    DrtVersionDTO reject(String pid, String note);

    /** Deprecate a PUBLISHED version (PUBLISHED → DEPRECATED). */
    DrtVersionDTO deprecate(String pid, String note, boolean impactAcknowledged);

    /** Retire a DEPRECATED version (DEPRECATED → RETIRED). */
    DrtVersionDTO retire(String pid, String note, boolean impactAcknowledged);

    /**
     * Delete a mutable draft-like version and clear its usage-index refs.
     * Published/deprecated/retired versions are never hard-deleted; callers must deprecate/retire instead.
     */
    DrtVersionDTO delete(String pid);

    /** Find a version by its own PID (tenant-scoped). Returns null when not found. */
    DrtVersionDTO findByPid(String pid);

    /** List all versions for a decision_code (tenant-scoped, newest first). */
    List<DrtVersionDTO> listByCode(String decisionCode);
}
