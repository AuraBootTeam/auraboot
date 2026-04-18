package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.service.SkillDraftNamer;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Learning Loop REST API for Mission Control HITL workflow.
 *
 * Endpoints:
 *   GET  /api/learning/drafts                     — list drafts, optional status filter
 *   GET  /api/learning/drafts/{pid}               — detail incl. recent shadow runs
 *   POST /api/learning/drafts/{pid}/review        — approve / reject with comment
 *   POST /api/learning/drafts/{pid}/auto-rename   — trigger LLM rename (best-effort)
 *
 * All endpoints are tenant-scoped via MetaContext.getCurrentTenantId().
 */
@Slf4j
@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningLoopController {

    private final JdbcTemplate jdbcTemplate;
    private final SkillDraftNamer namer;

    // =========================================================================
    // List
    // =========================================================================

    /**
     * List drafts for the current tenant. Filter by {@code status} (optional).
     * Default limit 50, max 200. Ordered by created_at DESC.
     */
    @GetMapping("/drafts")
    public ApiResponse<List<Map<String, Object>>> listDrafts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int capped = Math.min(Math.max(1, limit), 200);

        StringBuilder sql = new StringBuilder(
                "SELECT pid, draft_skill_code, source_pattern_hash, status, " +
                        "       reviewer_id, review_comment, " +
                        "       created_at, reviewed_at, shadow_started_at, promoted_at, " +
                        "       shadow_metrics::text AS shadow_metrics_json " +
                        "FROM ab_agent_skill_draft " +
                        "WHERE tenant_id = ? ");
        List<Object> params = new java.util.ArrayList<>();
        params.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql.append("AND status = ? ");
            params.add(status);
        }
        sql.append("ORDER BY created_at DESC LIMIT ?");
        params.add(capped);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        return ApiResponse.ok(rows);
    }

    // =========================================================================
    // Detail
    // =========================================================================

    /**
     * Full draft detail + up to 20 most-recent shadow runs + source pattern
     * stats. Returns 404 when not found.
     */
    @GetMapping("/drafts/{pid}")
    public ApiResponse<Map<String, Object>> getDraft(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Map<String, Object>> drafts = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id, draft_skill_code, contract_yaml, contract_hash, " +
                        "       source_pattern_hash, derived_from_runs::text AS derived_from_runs_json, " +
                        "       status, reviewer_id, review_comment, " +
                        "       shadow_metrics::text AS shadow_metrics_json, " +
                        "       promoted_to_skill_id, " +
                        "       created_at, reviewed_at, shadow_started_at, promoted_at " +
                        "FROM ab_agent_skill_draft WHERE pid = ? AND tenant_id = ?",
                pid, tenantId);
        if (drafts.isEmpty()) return ApiResponse.error(404, "draft not found");

        Map<String, Object> draft = new LinkedHashMap<>(drafts.get(0));

        // Source pattern stats
        String hash = (String) draft.get("source_pattern_hash");
        List<Map<String, Object>> patterns = jdbcTemplate.queryForList(
                "SELECT pattern_hash, pattern_signature::text AS signature_json, " +
                        "       invocation_count, success_rate, status, " +
                        "       first_seen_at, last_observed_at " +
                        "FROM ab_agent_learning_pattern WHERE pattern_hash = ? LIMIT 1", hash);
        draft.put("source_pattern", patterns.isEmpty() ? null : patterns.get(0));

        // Recent shadow runs
        List<Map<String, Object>> shadows = jdbcTemplate.queryForList(
                "SELECT pid, original_run_id, shadow_status, shadow_duration_ms, shadow_cost_usd, " +
                        "       original_status, original_duration_ms, original_cost_usd, " +
                        "       output_match, fidelity_match, output_diff::text AS output_diff_json, " +
                        "       created_at " +
                        "FROM ab_agent_shadow_run WHERE draft_id = ? " +
                        "ORDER BY created_at DESC LIMIT 20",
                pid);
        draft.put("recent_shadow_runs", shadows);

        return ApiResponse.ok(draft);
    }

    // =========================================================================
    // Review (approve / reject)
    // =========================================================================

    /**
     * Approve or reject a draft.
     * Body: {"decision": "approve"|"reject", "comment": "...optional"}
     * Transitions:
     *   approve: DRAFT_PENDING_REVIEW  → REVIEWED_OK
     *            PROMOTED_PENDING_HUMAN → ACTIVE
     *   reject:  any → REVIEWED_REJECTED
     */
    @PostMapping("/drafts/{pid}/review")
    public ApiResponse<Map<String, Object>> review(@PathVariable String pid,
                                                    @RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long reviewerId = MetaContext.getCurrentUserId();
        String decision = (String) body.get("decision");
        String comment = body.get("comment") == null ? null : body.get("comment").toString();

        if (!"approve".equals(decision) && !"reject".equals(decision)) {
            return ApiResponse.error(400, "decision must be 'approve' or 'reject'");
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ? AND tenant_id = ?",
                pid, tenantId);
        if (rows.isEmpty()) return ApiResponse.error(404, "draft not found");
        String currentStatus = (String) rows.get(0).get("status");

        String newStatus;
        String eventColumn;
        if ("reject".equals(decision)) {
            newStatus = "REVIEWED_REJECTED";
            eventColumn = "reviewed_at";
        } else if ("DRAFT_PENDING_REVIEW".equals(currentStatus)) {
            newStatus = "REVIEWED_OK";
            eventColumn = "reviewed_at";
        } else if ("PROMOTED_PENDING_HUMAN".equals(currentStatus)) {
            newStatus = "ACTIVE";
            eventColumn = "promoted_at";
        } else {
            return ApiResponse.error(409,
                    "cannot approve from status=" + currentStatus + " (allowed: DRAFT_PENDING_REVIEW | PROMOTED_PENDING_HUMAN)");
        }

        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_skill_draft " +
                        "SET status = ?, reviewer_id = ?, review_comment = ?, " +
                        "    " + eventColumn + " = NOW() " +
                        "WHERE pid = ? AND tenant_id = ? AND status = ?",
                newStatus, reviewerId, comment, pid, tenantId, currentStatus);

        if (updated != 1) {
            return ApiResponse.error(409, "draft concurrently modified; re-read and retry");
        }

        log.info("Learning Loop review: draft={} {} → {} by user={}",
                pid, currentStatus, newStatus, reviewerId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", pid);
        result.put("previous_status", currentStatus);
        result.put("status", newStatus);
        return ApiResponse.ok(result);
    }

    // =========================================================================
    // Auto-rename (LLM Namer trigger)
    // =========================================================================

    /**
     * Trigger the LLM Namer to propose a better skill_code + description.
     * Response tells the UI whether the draft got renamed (LLM call succeeded
     * and the proposal passed validation) or not.
     */
    @PostMapping("/drafts/{pid}/auto-rename")
    public ApiResponse<Map<String, Object>> autoRename(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        boolean renamed = namer.renameDraft(tenantId, pid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", pid);
        result.put("renamed", renamed);
        if (renamed) {
            String newCode = jdbcTemplate.queryForObject(
                    "SELECT draft_skill_code FROM ab_agent_skill_draft WHERE pid = ? AND tenant_id = ?",
                    String.class, pid, tenantId);
            result.put("new_code", newCode);
        }
        return ApiResponse.ok(result);
    }
}
