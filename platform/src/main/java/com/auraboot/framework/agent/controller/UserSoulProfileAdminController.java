package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.metrics.UserSoulProfileMetrics;
import com.auraboot.framework.agent.profile.UserSoulProfileStatus;
import com.auraboot.framework.agent.service.UserSoulProfileEditor;
import com.auraboot.framework.agent.service.UserSoulProfileEditor.EditResult;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Admin-facing User Soul Profile endpoints (plan §5.6 / PR-78).
 *
 * <p>Returns metadata only — <b>never</b> the {@code profile} JSONB content
 * or {@code edited_fields}. User Soul Profile is the user's property within
 * the tenant; admins get coverage and staleness visibility but not content
 * access. See plan §7 "Privacy &amp; control".
 */
@Slf4j
@RestController
@RequestMapping(UserSoulProfileAdminController.BASE_PATH)
public class UserSoulProfileAdminController {

    public static final String BASE_PATH = "/api/admin/user-soul-profiles";

    private final JdbcTemplate jdbcTemplate;
    private final UserSoulProfileEditor editor;
    private final UserSoulProfileMetrics metrics;

    @Autowired
    public UserSoulProfileAdminController(JdbcTemplate jdbcTemplate,
                                          UserSoulProfileEditor editor,
                                          UserSoulProfileMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.editor = editor;
        this.metrics = metrics;
    }

    // =========================================================================
    // GET / — per-tenant list, metadata only
    // =========================================================================

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false, defaultValue = "50") int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        ApiResponse<List<Map<String, Object>>> denied = guardTenantAdmin();
        if (denied != null) return denied;
        int capped = Math.min(Math.max(1, limit), 200);

        // NOTE: content columns (profile, edited_fields, source_memory_pids)
        // are deliberately excluded from the SELECT. Do not add them back
        // without revisiting plan §7.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, user_id, version, status, derivation_confidence, " +
                        "       activated_at, stale_flagged_at, created_at " +
                        "FROM ab_agent_user_soul_profile " +
                        "WHERE tenant_id = ? " +
                        "ORDER BY created_at DESC " +
                        "LIMIT ?",
                tenantId, capped);
        return ApiResponse.ok(rows);
    }

    // =========================================================================
    // GET /stats — aggregates only
    // =========================================================================

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Long tenantId = MetaContext.getCurrentTenantId();
        ApiResponse<Map<String, Object>> denied = guardTenantAdmin();
        if (denied != null) return denied;

        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put(UserSoulProfileStatus.DRAFT.code(), 0L);
        byStatus.put(UserSoulProfileStatus.ACTIVE.code(), 0L);
        byStatus.put(UserSoulProfileStatus.SUPERSEDED.code(), 0L);
        byStatus.put(UserSoulProfileStatus.ARCHIVED.code(), 0L);

        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT status, COUNT(*) AS n FROM ab_agent_user_soul_profile " +
                        "WHERE tenant_id = ? GROUP BY status",
                tenantId)) {
            String status = (String) row.get("status");
            long n = ((Number) row.get("n")).longValue();
            byStatus.put(status, n);
        }

        Long activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile " +
                        "WHERE tenant_id = ? AND status = ?",
                Long.class, tenantId, UserSoulProfileStatus.ACTIVE.code());

        Long staleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile " +
                        "WHERE tenant_id = ? AND status = ? AND stale_flagged_at IS NOT NULL",
                Long.class, tenantId, UserSoulProfileStatus.ACTIVE.code());

        Double avgConfidence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(AVG(derivation_confidence), 0) " +
                        "FROM ab_agent_user_soul_profile " +
                        "WHERE tenant_id = ? AND status = ?",
                Double.class, tenantId, UserSoulProfileStatus.ACTIVE.code());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active_count", activeCount == null ? 0L : activeCount);
        out.put("stale_count", staleCount == null ? 0L : staleCount);
        out.put("avg_confidence", avgConfidence == null ? 0.0d : avgConfidence);
        out.put("by_status", byStatus);
        return ApiResponse.ok(out);
    }

    // =========================================================================
    // POST /forget — admin-triggered forget-user cascade (PR-81 / Phase 9)
    // =========================================================================

    /**
     * Admin-triggered GDPR forget for another user's Soul Profile. Archives
     * every live row and inserts a tombstone the deriver honours forever.
     *
     * <p>Body:
     * <pre>{@code {"userId": "42", "reason": "gdpr_request"}}</pre>
     *
     * <p>Both fields are required. {@code reason} becomes a Prometheus label
     * (counter {@code auraboot_user_soul_profile_admin_forget_total}) so
     * callers should normalise to a short vocabulary
     * ({@code gdpr_request}, {@code account_closed}, {@code policy_violation},
     * {@code other}).
     *
     * <p>This endpoint deliberately does NOT expose the target user's
     * profile content in the response — only the resulting tombstone pid /
     * version / status metadata. The user-only {@code /export} endpoint
     * remains the single content-visibility surface for Soul Profile data.
     *
     * <p>Idempotency: when the target user has no rows, returns
     * {@code {"noop": true}} with 200. Repeated calls after a prior forget
     * still succeed (Editor inserts a fresh tombstone version).
     */
    @PostMapping("/forget")
    public ApiResponse<Map<String, Object>> forget(@RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        ApiResponse<Map<String, Object>> denied = guardTenantAdmin();
        if (denied != null) return denied;

        String targetUserId = requireStringField(body, "userId");
        String reason = requireStringField(body, "reason");
        if (targetUserId == null) return ApiResponse.error(400, "userId required");
        if (reason == null) return ApiResponse.error(400, "reason required");

        String actingAdminId = Objects.toString(MetaContext.getCurrentUserId(), "unknown");
        log.info("UserSoulProfileAdminController: admin={} forget target_user={} tenant={} reason={}",
                actingAdminId, targetUserId, tenantId, reason);

        try {
            // Insert audit row BEFORE the destructive call, so an exception
            // during forget still leaves a trail that the attempt was made.
            // When the target has no rows (cross-tenant isolation / ghost),
            // forgetProfile throws IllegalArgumentException and we roll the
            // audit row back below.
            insertAdminActionAudit(tenantId, actingAdminId, targetUserId,
                    ACTION_ADMIN_FORGET, reason);
            EditResult r = editor.forgetProfile(tenantId, targetUserId);
            metrics.recordAdminForget(tenantId, reason);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pid", r.pid());
            out.put("version", r.version());
            out.put("status", r.status());
            out.put("noop", false);
            out.put("target_user_id", targetUserId);
            out.put("reason", reason);
            return ApiResponse.ok(out);
        } catch (IllegalArgumentException nothingToDo) {
            // Editor throws when the user has no rows at all — either the
            // target genuinely has no profile, or (important cross-tenant
            // case) the target exists in a different tenant. Either way the
            // call is a noop: we must NOT record the metric and must NOT
            // leave an audit row, so the audit table only reflects real
            // destructive actions within the admin's own tenant.
            deleteLastAdminActionAudit(tenantId, actingAdminId, targetUserId, ACTION_ADMIN_FORGET);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("noop", true);
            out.put("message", "no profile to forget");
            out.put("target_user_id", targetUserId);
            out.put("reason", reason);
            return ApiResponse.ok(out);
        }
    }

    private static String requireStringField(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    // =========================================================================
    // Admin guard + audit helpers
    // =========================================================================

    static final String TENANT_ADMIN_ROLE_CODE = "tenant_admin";
    static final String ACTION_ADMIN_FORGET = "admin_forget";

    /**
     * Guard for destructive / privileged admin endpoints.
     *
     * <p>Returns a 409 {@code ApiResponse} when the caller does not hold the
     * {@code tenant_admin} role in the current tenant; returns {@code null}
     * otherwise (caller proceeds). 409 is chosen over 403 because the project
     * standard surfaces {@link IllegalStateException} conditions as 409 and
     * this guard expresses a state conflict ("you are not in a state that
     * permits this operation").
     *
     * <p>Role lookup goes through:
     * <pre>ab_tenant_member (user_id → id) → ab_user_role (member_id) → ab_role (code)</pre>
     * — matching the Phase-2 RBAC schema where {@code ab_user_role.member_id}
     * references {@code ab_tenant_member.id}. No caching: admin endpoints are
     * low-QPS and freshness matters more than latency.
     */
    private <T> ApiResponse<T> guardTenantAdmin() {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            return ApiResponse.error(409, "admin role required");
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_user_role ur " +
                        " JOIN ab_tenant_member tm ON ur.member_id = tm.id " +
                        " JOIN ab_role r ON ur.role_id = r.id " +
                        " WHERE tm.user_id = ? " +
                        "   AND ur.tenant_id = ? " +
                        "   AND r.code = ? " +
                        "   AND (ur.deleted_flag = FALSE OR ur.deleted_flag IS NULL) " +
                        "   AND ur.status = 'active' " +
                        "   AND (r.deleted_flag = FALSE OR r.deleted_flag IS NULL)",
                Long.class, userId, tenantId, TENANT_ADMIN_ROLE_CODE);
        if (count == null || count == 0) {
            log.warn("UserSoulProfileAdminController: admin guard rejected user={} tenant={}",
                    userId, tenantId);
            return ApiResponse.error(409, "admin role required");
        }
        return null;
    }

    private void insertAdminActionAudit(Long tenantId, String actingAdminId,
                                        String targetUserId, String action, String reason) {
        jdbcTemplate.update(
                "INSERT INTO ab_agent_user_soul_profile_admin_action " +
                        "(tenant_id, acting_admin_id, target_user_id, action, reason) " +
                        "VALUES (?, ?, ?, ?, ?)",
                tenantId, actingAdminId, targetUserId, action, reason);
    }

    /**
     * Roll back the most recent audit row for the (tenant, admin, target, action)
     * tuple. Used to keep the audit table free of noop attempts (cross-tenant
     * isolation or ghost-user forgets). Deletes at most one row so concurrent
     * unrelated audits are not affected.
     */
    private void deleteLastAdminActionAudit(Long tenantId, String actingAdminId,
                                            String targetUserId, String action) {
        jdbcTemplate.update(
                "DELETE FROM ab_agent_user_soul_profile_admin_action " +
                        "WHERE id = (SELECT id FROM ab_agent_user_soul_profile_admin_action " +
                        "            WHERE tenant_id = ? AND acting_admin_id = ? " +
                        "              AND target_user_id = ? AND action = ? " +
                        "            ORDER BY id DESC LIMIT 1)",
                tenantId, actingAdminId, targetUserId, action);
    }
}
