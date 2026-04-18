package com.auraboot.framework.agent.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public UserSoulProfileAdminController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // =========================================================================
    // GET / — per-tenant list, metadata only
    // =========================================================================

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false, defaultValue = "50") int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
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

        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put(STATUS_DRAFT, 0L);
        byStatus.put(STATUS_ACTIVE, 0L);
        byStatus.put(STATUS_SUPERSEDED, 0L);
        byStatus.put(STATUS_ARCHIVED, 0L);

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
                Long.class, tenantId, STATUS_ACTIVE);

        Long staleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile " +
                        "WHERE tenant_id = ? AND status = ? AND stale_flagged_at IS NOT NULL",
                Long.class, tenantId, STATUS_ACTIVE);

        Double avgConfidence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(AVG(derivation_confidence), 0) " +
                        "FROM ab_agent_user_soul_profile " +
                        "WHERE tenant_id = ? AND status = ?",
                Double.class, tenantId, STATUS_ACTIVE);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active_count", activeCount == null ? 0L : activeCount);
        out.put("stale_count", staleCount == null ? 0L : staleCount);
        out.put("avg_confidence", avgConfidence == null ? 0.0d : avgConfidence);
        out.put("by_status", byStatus);
        return ApiResponse.ok(out);
    }
}
