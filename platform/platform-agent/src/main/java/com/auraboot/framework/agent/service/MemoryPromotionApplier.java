package com.auraboot.framework.agent.service;

/**
 * Memory Promotion applier — approve / reject / retract transitions for
 * {@code ab_agent_memory_promotion} rows.
 *
 * <p><b>Interface contract (Phase 3, PR-67)</b>: this skeleton is introduced
 * together with {@code MemoryPromotionController} so the controller compiles
 * independently of Phase 2. The {@code @Service} implementation lands in
 * Phase 2 (PR-66) and provides the actual state-machine + metric emission
 * logic per plan §6.2.
 *
 * <p>All methods must:
 * <ul>
 *   <li>Enforce status guards (e.g. approve requires DRAFT_PENDING_REVIEW;
 *       retract requires PROMOTED_SHADOW).</li>
 *   <li>Be tenant-scoped via {@code MetaContext.getCurrentTenantId()}.</li>
 *   <li>Throw {@link IllegalStateException} when a status guard fails so the
 *       caller can map to HTTP 409.</li>
 *   <li>Throw {@link IllegalArgumentException} when the promotion is not
 *       found in the current tenant so the caller can map to HTTP 404.</li>
 *   <li>Emit the corresponding {@code auraboot_memory_promotion_decision_total}
 *       counter.</li>
 * </ul>
 *
 * @see "docs/plans/2026-04/2026-04-18-memory-promotion-design.md §6.2"
 */
public interface MemoryPromotionApplier {

    /**
     * Outcome of an approve/reject/retract operation.
     *
     * @param pid                  the promotion pid
     * @param previousStatus       status before the transition
     * @param newStatus            status after the transition
     * @param promotedMemoryPid    when {@code newStatus == PROMOTED_SHADOW},
     *                             the pid of the freshly-created
     *                             {@code ab_agent_memory} row; otherwise null
     */
    record EvaluationResult(String pid,
                            String previousStatus,
                            String newStatus,
                            String promotedMemoryPid) {}

    /**
     * Approve a DRAFT_PENDING_REVIEW proposal. Creates a shadow
     * {@code ab_agent_memory} row and flips the promotion to
     * {@code PROMOTED_SHADOW}. 7-day shadow window starts now.
     */
    EvaluationResult approve(String promotionPid, Long reviewerId, String comment);

    /**
     * Reject a DRAFT_PENDING_REVIEW proposal. {@code rejectReason} must be
     * one of the enum values defined by the
     * {@code chk_memory_promotion_reject_reason} CHECK constraint.
     */
    EvaluationResult reject(String promotionPid, Long reviewerId, String rejectReason, String comment);

    /**
     * Retract a PROMOTED_SHADOW proposal during the observation window.
     * Soft-deletes the associated {@code ab_agent_memory} row and flips the
     * promotion to {@code RETRACTED}.
     */
    EvaluationResult retract(String promotionPid, Long reviewerId, String reason);
}
