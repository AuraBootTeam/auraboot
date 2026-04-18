package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.service.MemoryPromotionApplier;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Memory Promotion REST API for Mission Control (plan §6.6, PR-67).
 *
 * <p>Endpoints:
 * <pre>
 *   GET  /api/memory/promotions                          — list (filter + sort)
 *   GET  /api/memory/promotions/{pid}                    — detail + source memories
 *   GET  /api/memory/promotions/{pid}/provenance         — full chain
 *   POST /api/memory/promotions/{pid}/review             — approve | reject
 *   POST /api/memory/promotions/{pid}/retract            — retract during shadow
 *   POST /api/memory/promotions/batch-approve            — bulk approve (≥0.80 confidence)
 *   GET  /api/memory/promotions/stats                    — dashboard + alert counts
 * </pre>
 *
 * <p>All endpoints are tenant-scoped via {@link MetaContext#getCurrentTenantId()}.
 * State transitions are delegated to {@link MemoryPromotionApplier}, whose
 * {@code @Service} implementation lands in Phase 2 (PR-66). Until Phase 2
 * merges, mutation endpoints return 503.
 */
@Slf4j
@RestController
@RequestMapping("/api/memory/promotions")
public class MemoryPromotionController {

    // Valid reject_reason values — mirrors chk_memory_promotion_reject_reason.
    static final Set<String> VALID_REJECT_REASONS = Set.of(
            "too_specific", "contains_pii", "outdated", "wrong", "duplicate", "other");

    private static final Set<String> VALID_STATUS_FILTERS = Set.of(
            "DRAFT_PENDING_REVIEW", "REVIEWED_REJECTED", "PROMOTED_SHADOW",
            "ACTIVE", "RETRACTED", "DISCARDED", "EXPIRED");

    private static final Set<String> VALID_REASON_CODES = Set.of(
            "cross_user_agreement", "implicit_co_sign", "importance_spike", "session_upgrade");

    private static final int MAX_BATCH = 50;
    private static final BigDecimal BATCH_CONF_THRESHOLD = new BigDecimal("0.80");

    private final JdbcTemplate jdbcTemplate;
    private final MemoryPromotionApplier applier;

    @Autowired
    public MemoryPromotionController(JdbcTemplate jdbcTemplate,
                                     @Autowired(required = false) MemoryPromotionApplier applier) {
        this.jdbcTemplate = jdbcTemplate;
        this.applier = applier;
    }

    // =========================================================================
    // GET / — list
    // =========================================================================

    /**
     * List promotions for the current tenant.
     *
     * @param status reserved for filter; default {@code DRAFT_PENDING_REVIEW}
     * @param reason optional {@code reason_code} filter
     * @param limit  default 50, max 200
     * @param sort   {@code confidence_desc} (default when status=DRAFT_PENDING_REVIEW)
     *               or {@code created_desc} (default otherwise)
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false) String sort) {

        Long tenantId = MetaContext.getCurrentTenantId();
        String effectiveStatus = (status == null || status.isBlank()) ? "DRAFT_PENDING_REVIEW" : status;
        if (!VALID_STATUS_FILTERS.contains(effectiveStatus)) {
            return ApiResponse.error(400, "invalid status filter: " + effectiveStatus);
        }
        if (reason != null && !reason.isBlank() && !VALID_REASON_CODES.contains(reason)) {
            return ApiResponse.error(400, "invalid reason_code filter: " + reason);
        }
        int capped = Math.min(Math.max(1, limit), 200);

        String effectiveSort = (sort == null || sort.isBlank())
                ? ("DRAFT_PENDING_REVIEW".equals(effectiveStatus) ? "confidence_desc" : "created_desc")
                : sort;
        String orderClause;
        if ("confidence_desc".equals(effectiveSort)) {
            orderClause = "ORDER BY confidence_score DESC NULLS LAST, created_at DESC ";
        } else if ("created_desc".equals(effectiveSort)) {
            orderClause = "ORDER BY created_at DESC ";
        } else {
            return ApiResponse.error(400, "invalid sort: " + effectiveSort);
        }

        StringBuilder sql = new StringBuilder(
                "SELECT pid, tenant_id, source_scope, source_memory_pid, " +
                        "       source_memory_pids::text AS source_memory_pids_json, target_scope, " +
                        "       category, proposed_title, proposed_content, proposed_importance, " +
                        "       reason_code, reason_detail::text AS reason_detail_json, " +
                        "       confidence_score, similarity_score, ai_rationale, " +
                        "       status, reviewer_id, review_comment, reject_reason, " +
                        "       promoted_memory_pid, shadow_started_at, shadow_ends_at, activated_at, " +
                        "       created_at, reviewed_at, updated_at " +
                        "FROM ab_agent_memory_promotion " +
                        "WHERE tenant_id = ? AND status = ? ");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(effectiveStatus);

        if (reason != null && !reason.isBlank()) {
            sql.append("AND reason_code = ? ");
            params.add(reason);
        }
        sql.append(orderClause).append("LIMIT ?");
        params.add(capped);

        return ApiResponse.ok(jdbcTemplate.queryForList(sql.toString(), params.toArray()));
    }

    // =========================================================================
    // GET /{pid} — detail
    // =========================================================================

    /**
     * Promotion detail + joined source memories (with author info from
     * {@code ab_user} when the user row still exists).
     */
    @GetMapping("/{pid}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id, source_scope, source_memory_pid, " +
                        "       source_memory_pids::text AS source_memory_pids_json, target_scope, " +
                        "       category, proposed_title, proposed_content, proposed_importance, " +
                        "       reason_code, reason_detail::text AS reason_detail_json, " +
                        "       confidence_score, similarity_score, ai_rationale, " +
                        "       status, reviewer_id, review_comment, reject_reason, " +
                        "       promoted_memory_pid, shadow_started_at, shadow_ends_at, activated_at, " +
                        "       created_at, reviewed_at, updated_at " +
                        "FROM ab_agent_memory_promotion WHERE pid = ? AND tenant_id = ?",
                pid, tenantId);
        if (rows.isEmpty()) return ApiResponse.error(404, "promotion not found");

        Map<String, Object> data = new LinkedHashMap<>(rows.get(0));
        data.put("source_memories", loadSourceMemories(data, tenantId));
        return ApiResponse.ok(data);
    }

    // =========================================================================
    // GET /{pid}/provenance — full chain
    // =========================================================================

    /**
     * Full provenance chain: promotion row, source memories, the promoted
     * memory (when present), and any upstream promotions (follows
     * {@code promoted_from_pid} back).
     */
    @GetMapping("/{pid}/provenance")
    public ApiResponse<Map<String, Object>> provenance(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id, source_scope, source_memory_pid, " +
                        "       source_memory_pids::text AS source_memory_pids_json, target_scope, " +
                        "       category, proposed_title, proposed_content, proposed_importance, " +
                        "       reason_code, reason_detail::text AS reason_detail_json, " +
                        "       confidence_score, similarity_score, ai_rationale, " +
                        "       status, reviewer_id, review_comment, reject_reason, " +
                        "       promoted_memory_pid, shadow_started_at, shadow_ends_at, activated_at, " +
                        "       created_at, reviewed_at, updated_at " +
                        "FROM ab_agent_memory_promotion WHERE pid = ? AND tenant_id = ?",
                pid, tenantId);
        if (rows.isEmpty()) return ApiResponse.error(404, "promotion not found");

        Map<String, Object> promotion = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> sourceMemories = loadSourceMemories(promotion, tenantId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("promotion", promotion);
        out.put("source_memories", sourceMemories);

        // Promoted memory when applicable
        Map<String, Object> promotedMemory = null;
        String promotedPid = (String) promotion.get("promoted_memory_pid");
        if (promotedPid != null) {
            List<Map<String, Object>> mrows = jdbcTemplate.queryForList(
                    "SELECT pid, tenant_id, scope, scope_key, category, " +
                            "       memory_title, memory_content, importance, shareable, " +
                            "       shadow_mode, promoted_from_pid, created_at, updated_at " +
                            "FROM ab_agent_memory WHERE pid = ? AND tenant_id = ?",
                    promotedPid, tenantId);
            if (!mrows.isEmpty()) promotedMemory = mrows.get(0);
        }
        out.put("promoted_memory", promotedMemory);

        // Walk upstream promotions via source memories' promoted_from_pid
        List<Map<String, Object>> upstream = new ArrayList<>();
        for (Map<String, Object> srcMem : sourceMemories) {
            Object upstreamPid = srcMem.get("promoted_from_pid");
            if (upstreamPid == null) continue;
            List<Map<String, Object>> uprows = jdbcTemplate.queryForList(
                    "SELECT pid, status, source_scope, target_scope, category, " +
                            "       proposed_title, created_at, activated_at " +
                            "FROM ab_agent_memory_promotion WHERE pid = ? AND tenant_id = ?",
                    upstreamPid, tenantId);
            upstream.addAll(uprows);
        }
        out.put("upstream_promotions", upstream);

        return ApiResponse.ok(out);
    }

    // =========================================================================
    // POST /{pid}/review — approve | reject
    // =========================================================================

    /**
     * Approve or reject a promotion.
     * <p>Body: {@code {"decision": "approve"|"reject", "comment": "...",
     * "reject_reason": "..." }}
     */
    @PostMapping("/{pid}/review")
    public ApiResponse<Map<String, Object>> review(@PathVariable String pid,
                                                   @RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long reviewerId = MetaContext.getCurrentUserId();
        String decision = body == null ? null : (String) body.get("decision");
        String comment = (body == null || body.get("comment") == null) ? null : body.get("comment").toString();

        if (!"approve".equals(decision) && !"reject".equals(decision)) {
            return ApiResponse.error(400, "decision must be 'approve' or 'reject'");
        }

        // Verify the promotion belongs to this tenant before touching applier.
        if (!existsForTenant(pid, tenantId)) {
            return ApiResponse.error(404, "promotion not found");
        }

        if (applier == null) {
            return ApiResponse.error(503,
                    "Memory Promotion Phase 2 not yet deployed — applier unavailable");
        }

        try {
            MemoryPromotionApplier.EvaluationResult r;
            if ("reject".equals(decision)) {
                String rejectReason = body.get("reject_reason") == null
                        ? null : body.get("reject_reason").toString();
                if (rejectReason == null || !VALID_REJECT_REASONS.contains(rejectReason)) {
                    return ApiResponse.error(400,
                            "reject_reason required; must be one of " + VALID_REJECT_REASONS);
                }
                r = applier.reject(pid, reviewerId, rejectReason, comment);
            } else {
                r = applier.approve(pid, reviewerId, comment);
            }
            return ApiResponse.ok(toResultMap(r));
        } catch (IllegalStateException conflict) {
            return ApiResponse.error(409, conflict.getMessage());
        } catch (IllegalArgumentException notFound) {
            return ApiResponse.error(404, notFound.getMessage());
        }
    }

    // =========================================================================
    // POST /{pid}/retract
    // =========================================================================

    /**
     * Retract a {@code PROMOTED_SHADOW} promotion. Body: {@code {"reason": "..."}}.
     * Reason is free-form (not the reject_reason enum).
     */
    @PostMapping("/{pid}/retract")
    public ApiResponse<Map<String, Object>> retract(@PathVariable String pid,
                                                    @RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long reviewerId = MetaContext.getCurrentUserId();
        String reason = (body == null || body.get("reason") == null) ? null : body.get("reason").toString();
        if (reason == null || reason.isBlank()) {
            return ApiResponse.error(400, "reason required");
        }

        if (!existsForTenant(pid, tenantId)) {
            return ApiResponse.error(404, "promotion not found");
        }

        if (applier == null) {
            return ApiResponse.error(503,
                    "Memory Promotion Phase 2 not yet deployed — applier unavailable");
        }

        try {
            MemoryPromotionApplier.EvaluationResult r = applier.retract(pid, reviewerId, reason);
            return ApiResponse.ok(toResultMap(r));
        } catch (IllegalStateException conflict) {
            return ApiResponse.error(409, conflict.getMessage());
        } catch (IllegalArgumentException notFound) {
            return ApiResponse.error(404, notFound.getMessage());
        }
    }

    // =========================================================================
    // POST /batch-approve
    // =========================================================================

    /**
     * Bulk approve. Only proposals with {@code confidence_score >= 0.80} are
     * forwarded to the applier; lower-confidence pids are returned in
     * {@code failures}. Max {@value #MAX_BATCH} pids per request.
     */
    @PostMapping("/batch-approve")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> batchApprove(@RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long reviewerId = MetaContext.getCurrentUserId();
        Object pidsObj = body == null ? null : body.get("pids");
        if (!(pidsObj instanceof List)) {
            return ApiResponse.error(400, "pids must be an array");
        }
        List<?> rawPids = (List<?>) pidsObj;
        if (rawPids.isEmpty()) {
            return ApiResponse.error(400, "pids must not be empty");
        }
        if (rawPids.size() > MAX_BATCH) {
            return ApiResponse.error(400, "pids exceeds max batch size of " + MAX_BATCH);
        }
        String comment = (body.get("comment") == null) ? null : body.get("comment").toString();

        if (applier == null) {
            return ApiResponse.error(503,
                    "Memory Promotion Phase 2 not yet deployed — applier unavailable");
        }

        List<String> approved = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();

        for (Object obj : rawPids) {
            String pid = obj == null ? null : obj.toString();
            if (pid == null || pid.isBlank()) {
                failed.add(failure("", "pid is null or blank"));
                continue;
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT confidence_score, status FROM ab_agent_memory_promotion " +
                            "WHERE pid = ? AND tenant_id = ?",
                    pid, tenantId);
            if (rows.isEmpty()) {
                failed.add(failure(pid, "not found"));
                continue;
            }
            BigDecimal confidence = (BigDecimal) rows.get(0).get("confidence_score");
            if (confidence == null || confidence.compareTo(BATCH_CONF_THRESHOLD) < 0) {
                failed.add(failure(pid, "confidence below threshold (" + BATCH_CONF_THRESHOLD + ")"));
                continue;
            }
            try {
                applier.approve(pid, reviewerId, comment);
                approved.add(pid);
            } catch (IllegalStateException | IllegalArgumentException ex) {
                failed.add(failure(pid, ex.getMessage()));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approved", approved);
        out.put("failed", failed);
        return ApiResponse.ok(out);
    }

    // =========================================================================
    // GET /stats
    // =========================================================================

    /**
     * Per-tenant counts — used by the Mission Control dashboard and the
     * Grafana gauge's tenant breakdown.
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Long tenantId = MetaContext.getCurrentTenantId();

        // by_status — pre-seed every known status for stable shape
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String s : VALID_STATUS_FILTERS) byStatus.put(s, 0L);
        long total = 0;
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT status, COUNT(*) AS n FROM ab_agent_memory_promotion " +
                        "WHERE tenant_id = ? GROUP BY status",
                tenantId)) {
            String status = (String) row.get("status");
            long n = ((Number) row.get("n")).longValue();
            byStatus.put(status, n);
            total += n;
        }

        // by_reason_code
        Map<String, Long> byReason = new LinkedHashMap<>();
        for (String r : VALID_REASON_CODES) byReason.put(r, 0L);
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT reason_code, COUNT(*) AS n FROM ab_agent_memory_promotion " +
                        "WHERE tenant_id = ? AND reason_code IS NOT NULL GROUP BY reason_code",
                tenantId)) {
            String code = (String) row.get("reason_code");
            long n = ((Number) row.get("n")).longValue();
            byReason.merge(code, n, Long::sum);
        }

        // by_reject_reason
        Map<String, Long> byRejectReason = new LinkedHashMap<>();
        for (String r : VALID_REJECT_REASONS) byRejectReason.put(r, 0L);
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT reject_reason, COUNT(*) AS n FROM ab_agent_memory_promotion " +
                        "WHERE tenant_id = ? AND reject_reason IS NOT NULL GROUP BY reject_reason",
                tenantId)) {
            String code = (String) row.get("reject_reason");
            long n = ((Number) row.get("n")).longValue();
            byRejectReason.merge(code, n, Long::sum);
        }

        Long pendingOld = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_promotion " +
                        "WHERE tenant_id = ? AND status = 'DRAFT_PENDING_REVIEW' " +
                        "  AND created_at < NOW() - INTERVAL '7 days'",
                Long.class, tenantId);

        Double oldestAge = jdbcTemplate.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(created_at))), 0) " +
                        "  FROM ab_agent_memory_promotion " +
                        " WHERE tenant_id = ? AND status = 'DRAFT_PENDING_REVIEW'",
                Double.class, tenantId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("by_status", byStatus);
        out.put("by_reason_code", byReason);
        out.put("by_reject_reason", byRejectReason);
        out.put("pending_older_than_7d", pendingOld == null ? 0L : pendingOld);
        out.put("oldest_pending_age_seconds", oldestAge == null ? 0.0d : oldestAge);
        return ApiResponse.ok(out);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean existsForTenant(String pid, Long tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT 1 FROM ab_agent_memory_promotion WHERE pid = ? AND tenant_id = ?",
                pid, tenantId);
        return !rows.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadSourceMemories(Map<String, Object> promotion, Long tenantId) {
        List<String> pids = new ArrayList<>();
        Object singlePid = promotion.get("source_memory_pid");
        if (singlePid != null) pids.add(singlePid.toString());
        // source_memory_pids_json may contain a JSON array of pids
        Object pidsJson = promotion.get("source_memory_pids_json");
        if (pidsJson != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(pidsJson.toString());
                if (node.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode n : node) {
                        if (n.isTextual()) {
                            String v = n.asText();
                            if (!pids.contains(v)) pids.add(v);
                        }
                    }
                }
            } catch (Exception parseErr) {
                log.warn("source_memory_pids_json parse failure for promotion={}: {}",
                        promotion.get("pid"), parseErr.getMessage());
            }
        }
        if (pids.isEmpty()) return List.of();

        String placeholders = String.join(",", java.util.Collections.nCopies(pids.size(), "?"));
        List<Object> params = new ArrayList<>(pids.size() + 1);
        params.addAll(pids);
        params.add(tenantId);
        // For user-scope memories scope_key holds the author user id (stringified);
        // cast to BIGINT for the join, keeping non-user-scope rows joinable too
        // (LEFT JOIN returns nulls when scope_key is not a bigint / not a user scope).
        String sql =
                "SELECT m.pid, m.tenant_id, m.scope, m.scope_key, m.category, " +
                        "       m.memory_title, m.memory_content, m.importance, m.shareable, " +
                        "       m.shadow_mode, m.promoted_from_pid, " +
                        "       m.created_at, m.updated_at, " +
                        "       u.id AS author_user_id, u.email AS author_email, u.user_name AS author_user_name " +
                        "FROM ab_agent_memory m " +
                        "LEFT JOIN ab_user u ON m.scope = 'user' " +
                        "                    AND m.scope_key ~ '^[0-9]+$' " +
                        "                    AND u.id = m.scope_key::BIGINT " +
                        "WHERE m.pid IN (" + placeholders + ") AND m.tenant_id = ?";
        return jdbcTemplate.queryForList(sql, params.toArray());
    }

    private static Map<String, Object> failure(String pid, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", pid);
        m.put("reason", reason);
        return m;
    }

    private static Map<String, Object> toResultMap(MemoryPromotionApplier.EvaluationResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pid", r.pid());
        out.put("previous_status", r.previousStatus());
        out.put("status", r.newStatus());
        out.put("promoted_memory_pid", r.promotedMemoryPid());
        return out;
    }
}
