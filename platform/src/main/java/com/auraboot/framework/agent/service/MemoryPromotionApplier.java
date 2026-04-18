package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.metrics.MemoryPromotionMetrics;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Set;

/**
 * Memory Promotion applier (PR-66 Phase 2, plan §6.2).
 *
 * <p>Synchronous executor for reviewer decisions on
 * {@code ab_agent_memory_promotion} rows. Every state transition uses the
 * {@code WHERE pid=? AND status=?} guard pattern (mirrors {@code
 * PromotionEvaluator} post-PR-53) and throws
 * {@link ConcurrentModificationException} when the guarded UPDATE affects
 * zero rows.
 *
 * <p>Status transitions handled here:
 * <ul>
 *   <li>{@code DRAFT_PENDING_REVIEW → PROMOTED_SHADOW} via {@link #approve}</li>
 *   <li>{@code DRAFT_PENDING_REVIEW → REVIEWED_REJECTED} via {@link #reject}</li>
 *   <li>{@code PROMOTED_SHADOW     → RETRACTED}         via {@link #retract}</li>
 * </ul>
 *
 * <p>{@code PROMOTED_SHADOW → ACTIVE} is handled by
 * {@link MemoryPromotionActivator} (scheduled, not reviewer-driven).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryPromotionApplier {

    /** Plan §4 — status lattice values. Kept as constants to avoid magic strings. */
    public static final String STATUS_DRAFT = "DRAFT_PENDING_REVIEW";
    public static final String STATUS_REJECTED = "REVIEWED_REJECTED";
    public static final String STATUS_SHADOW = "PROMOTED_SHADOW";
    public static final String STATUS_RETRACTED = "RETRACTED";
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** Allowed reject_reason values (mirrors CHECK constraint in schema.sql). */
    public static final Set<String> ALLOWED_REJECT_REASONS = Set.of(
            "too_specific", "contains_pii", "outdated", "wrong", "duplicate", "other");

    /** Shadow-window length before Activator flips to ACTIVE. */
    public static final String SHADOW_WINDOW_INTERVAL = "7 days";

    private final JdbcTemplate jdbcTemplate;
    private final AgentMemoryService agentMemoryService;
    private final MemoryPromotionMetrics metrics;

    // ================================================================
    // Approve — draft → shadow
    // ================================================================

    @Transactional
    public EvaluationResult approve(String promotionPid, Long reviewerId, String comment) {
        Map<String, Object> row = loadPromotionRow(promotionPid);
        String currentStatus = (String) row.get("status");
        if (!STATUS_DRAFT.equals(currentStatus)) {
            throw new ConcurrentModificationException(
                    "Promotion " + promotionPid + " is not in " + STATUS_DRAFT
                            + " (was: " + currentStatus + ")");
        }

        Long tenantId = ((Number) row.get("tenant_id")).longValue();
        String targetScope = (String) row.get("target_scope");
        String category = (String) row.get("category");
        String title = (String) row.get("proposed_title");
        String content = (String) row.get("proposed_content");
        int importance = row.get("proposed_importance") == null
                ? 5 : ((Number) row.get("proposed_importance")).intValue();

        // 1. Create the target-scope memory via the existing scoped API.
        //    scope_key is null — promoted memories are tenant-wide (or global
        //    when target_scope='global' in future); AgentMemoryService
        //    validates that 'user' requires a non-blank scope_key, and we
        //    never promote TO user scope (the CHECK constraint forbids it).
        String newMemoryPid = agentMemoryService.createScopedMemory(
                tenantId, /*agentCode*/ "default",
                /*memoryType*/ "fact", category,
                title, content, importance, /*shareable*/ true,
                targetScope, /*scopeKey*/ null);

        // 2. Flip shadow_mode + provenance pointer on the new memory row.
        jdbcTemplate.update(
                "UPDATE ab_agent_memory "
                        + "SET shadow_mode = TRUE, promoted_from_pid = ?, updated_at = NOW() "
                        + "WHERE pid = ?",
                promotionPid, newMemoryPid);

        // 3. Concurrency-safe status flip on the promotion row.
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_memory_promotion "
                        + "SET status = ?, reviewer_id = ?, review_comment = ?, "
                        + "    promoted_memory_pid = ?, "
                        + "    shadow_started_at = NOW(), "
                        + "    shadow_ends_at    = NOW() + INTERVAL '" + SHADOW_WINDOW_INTERVAL + "', "
                        + "    reviewed_at = NOW(), updated_at = NOW() "
                        + "WHERE pid = ? AND status = ?",
                STATUS_SHADOW, reviewerId, comment, newMemoryPid, promotionPid, STATUS_DRAFT);
        if (updated != 1) {
            throw new ConcurrentModificationException(
                    "Promotion " + promotionPid + " status changed concurrently during approve");
        }

        metrics.recordDecision(tenantId, MemoryPromotionMetrics.DECISION_APPROVE, null);
        log.info("MemoryPromotionApplier.approve: pid={} → {} memory={}",
                promotionPid, STATUS_SHADOW, newMemoryPid);

        return EvaluationResult.builder()
                .pid(promotionPid)
                .previousStatus(STATUS_DRAFT)
                .newStatus(STATUS_SHADOW)
                .promotedMemoryPid(newMemoryPid)
                .build();
    }

    // ================================================================
    // Reject — draft → rejected
    // ================================================================

    @Transactional
    public EvaluationResult reject(String promotionPid, Long reviewerId,
                                    String rejectReason, String comment) {
        if (rejectReason == null || !ALLOWED_REJECT_REASONS.contains(rejectReason)) {
            throw new IllegalArgumentException(
                    "Invalid reject_reason '" + rejectReason + "'; must be one of "
                            + ALLOWED_REJECT_REASONS);
        }
        Map<String, Object> row = loadPromotionRow(promotionPid);
        Long tenantId = ((Number) row.get("tenant_id")).longValue();

        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_memory_promotion "
                        + "SET status = ?, reviewer_id = ?, reject_reason = ?, "
                        + "    review_comment = ?, reviewed_at = NOW(), updated_at = NOW() "
                        + "WHERE pid = ? AND status = ?",
                STATUS_REJECTED, reviewerId, rejectReason, comment, promotionPid, STATUS_DRAFT);
        if (updated != 1) {
            throw new ConcurrentModificationException(
                    "Promotion " + promotionPid + " is not in " + STATUS_DRAFT
                            + " or changed concurrently");
        }

        metrics.recordDecision(tenantId, MemoryPromotionMetrics.DECISION_REJECT, rejectReason);
        log.info("MemoryPromotionApplier.reject: pid={} reason={}", promotionPid, rejectReason);

        return EvaluationResult.builder()
                .pid(promotionPid)
                .previousStatus(STATUS_DRAFT)
                .newStatus(STATUS_REJECTED)
                .build();
    }

    // ================================================================
    // Retract — shadow → retracted (soft-delete the memory)
    // ================================================================

    @Transactional
    public EvaluationResult retract(String promotionPid, Long reviewerId, String reason) {
        Map<String, Object> row = loadPromotionRow(promotionPid);
        String currentStatus = (String) row.get("status");
        if (!STATUS_SHADOW.equals(currentStatus)) {
            throw new ConcurrentModificationException(
                    "Promotion " + promotionPid + " is not in " + STATUS_SHADOW
                            + " (was: " + currentStatus + "); retraction only valid "
                            + "during the shadow window");
        }

        Long tenantId = ((Number) row.get("tenant_id")).longValue();
        String promotedMemoryPid = (String) row.get("promoted_memory_pid");

        // 1. Soft-delete the shadow memory row (if still present).
        if (promotedMemoryPid != null) {
            jdbcTemplate.update(
                    "UPDATE ab_agent_memory "
                            + "SET deleted_flag = TRUE, updated_at = NOW() "
                            + "WHERE pid = ?",
                    promotedMemoryPid);
        }

        // 2. Concurrency-safe status flip on the promotion row.
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_memory_promotion "
                        + "SET status = ?, review_comment = ?, reviewer_id = COALESCE(?, reviewer_id), "
                        + "    updated_at = NOW() "
                        + "WHERE pid = ? AND status = ?",
                STATUS_RETRACTED, reason, reviewerId, promotionPid, STATUS_SHADOW);
        if (updated != 1) {
            throw new ConcurrentModificationException(
                    "Promotion " + promotionPid + " status changed concurrently during retract");
        }

        metrics.recordShadowRetraction(tenantId);
        metrics.recordDecision(tenantId, MemoryPromotionMetrics.DECISION_RETRACT, null);
        log.info("MemoryPromotionApplier.retract: pid={} memory={} reason={}",
                promotionPid, promotedMemoryPid, reason);

        return EvaluationResult.builder()
                .pid(promotionPid)
                .previousStatus(STATUS_SHADOW)
                .newStatus(STATUS_RETRACTED)
                .promotedMemoryPid(promotedMemoryPid)
                .build();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private Map<String, Object> loadPromotionRow(String promotionPid) {
        try {
            return jdbcTemplate.queryForMap(
                    "SELECT pid, tenant_id, target_scope, category, proposed_title, "
                            + "       proposed_content, proposed_importance, status, "
                            + "       promoted_memory_pid "
                            + "FROM ab_agent_memory_promotion WHERE pid = ?",
                    promotionPid);
        } catch (EmptyResultDataAccessException e) {
            throw new ConcurrentModificationException("Promotion " + promotionPid + " not found");
        }
    }

    // ================================================================
    // DTO
    // ================================================================

    @Data
    @Builder
    public static class EvaluationResult {
        private String pid;
        private String previousStatus;
        private String newStatus;
        private String promotedMemoryPid;
    }
}
