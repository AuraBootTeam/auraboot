package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.dto.replay.ShadowRunAggregation;
import com.auraboot.framework.agent.dto.replay.ShadowRunListItem;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;

/**
 * D.5 Phase 1 — Shadow Runs comparison admin surface.
 *
 * <p>Surfaces the Learning Loop's {@code ab_agent_shadow_run} records grouped
 * by Skill Draft so an operator can see, at a glance, which drafts are
 * tracking production well enough to promote and which are diverging. The
 * existing {@code /api/learning/drafts/{pid}/shadow-runs} endpoint exposes
 * raw rows for a single draft; this controller adds the cross-draft
 * aggregation view that the Replay UI was missing.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/admin/shadow-runs/aggregations} — one row per draft
 *       with at least one shadow run; sorted by {@code latestAt DESC}.</li>
 *   <li>{@code GET /api/admin/shadow-runs?draftId=X} — paginated drilldown
 *       of individual rows belonging to {@code draftId}.</li>
 *   <li>{@code GET /api/admin/shadow-runs/{shadowRunPid}} — single row,
 *       returns 404 outside the caller's tenant.</li>
 * </ul>
 *
 * <p><b>Authorisation.</b> Lives under {@code /api/admin/**}; the platform
 * {@link com.auraboot.framework.application.security.AdminRoleInterceptor}
 * already enforces tenant-admin role, mirroring {@link AgentRunController}.
 *
 * <p><b>Tenant isolation.</b> Every query manually appends
 * {@code WHERE tenant_id = ?} sourced from {@link MetaContext} — JdbcTemplate
 * does not auto-attach the predicate the way the MyBatis interceptor does.
 *
 * <p><b>Read-only.</b> No mutations. Promotion / approval flows live on the
 * existing Learning Loop controller.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/shadow-runs")
public class AdminShadowRunController {

    /** Hard cap on aggregation list — tenants rarely produce more drafts. */
    private static final int MAX_AGGREGATIONS = 200;

    /** Hard cap on per-draft drilldown page size. */
    private static final int MAX_PAGE_SIZE = 200;

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public AdminShadowRunController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // =========================================================================
    // GET /aggregations — grouped-by-draft KPIs
    // =========================================================================

    /**
     * {@code GET /api/admin/shadow-runs/aggregations}
     *
     * <p>Returns at most {@link #MAX_AGGREGATIONS} rows. The query LEFT-JOINs
     * the draft table so a draft with zero shadow runs would still appear —
     * but we filter those out at the SQL level (HAVING run_count > 0) because
     * the Replay UI's empty state for a draft is "no runs yet" and the page
     * empty state is "no drafts have been shadowed yet".
     */
    @GetMapping("/aggregations")
    public ApiResponse<List<ShadowRunAggregation>> listAggregations() {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Group on draft_id and project the three KPI columns. Use FILTER
        // (WHERE ... IS NOT NULL) so rates only count rows where the match
        // flag was actually populated (the Learning Loop sometimes leaves
        // them NULL while still running). Same for cost_delta — when either
        // side is NULL the row contributes 0 to the sum, but if every row
        // has both sides NULL the SUM is also NULL via the COALESCE.
        String sql =
                "SELECT s.draft_id, " +
                "       d.draft_skill_code, " +
                "       d.status        AS draft_status, " +
                "       COUNT(*)        AS run_count, " +
                "       COUNT(*) FILTER (WHERE s.fidelity_match IS NOT NULL) AS fidelity_samples, " +
                "       COUNT(*) FILTER (WHERE s.output_match   IS NOT NULL) AS output_samples, " +
                "       AVG(CASE WHEN s.fidelity_match IS TRUE THEN 1.0 " +
                "                WHEN s.fidelity_match IS FALSE THEN 0.0 END) AS fidelity_match_rate, " +
                "       AVG(CASE WHEN s.output_match   IS TRUE THEN 1.0 " +
                "                WHEN s.output_match   IS FALSE THEN 0.0 END) AS output_match_rate, " +
                "       (COALESCE(SUM(s.shadow_cost_usd), 0) - COALESCE(SUM(s.original_cost_usd), 0)) " +
                "                       AS cost_delta, " +
                "       MAX(s.created_at) AS latest_at " +
                "  FROM ab_agent_shadow_run s " +
                "  JOIN ab_agent_skill_draft d " +
                "    ON d.pid = s.draft_id " +
                "   AND d.tenant_id = s.tenant_id " +
                " WHERE s.tenant_id = ? " +
                " GROUP BY s.draft_id, d.draft_skill_code, d.status " +
                "HAVING COUNT(*) > 0 " +
                " ORDER BY MAX(s.created_at) DESC " +
                " LIMIT ?";

        List<ShadowRunAggregation> rows = jdbcTemplate.query(
                sql, AGGREGATION_ROW_MAPPER, tenantId, MAX_AGGREGATIONS);
        if (rows.isEmpty()) {
            return ApiResponse.ok(Collections.emptyList());
        }
        return ApiResponse.ok(rows);
    }

    // =========================================================================
    // GET / — drilldown rows for a draft
    // =========================================================================

    /**
     * {@code GET /api/admin/shadow-runs?draftId=X&pageNum=0&pageSize=20}
     *
     * <p>Returns shadow-run rows for {@code draftId}, ordered by
     * {@code created_at DESC}. {@code pageNum} is 0-based.
     *
     * <p>Returns 400 when {@code draftId} is missing, 404 when the draft is
     * not visible to the caller (foreign tenant, or not found at all).
     */
    @GetMapping
    public ApiResponse<List<ShadowRunListItem>> listForDraft(
            @RequestParam(name = "draftId") String draftId,
            @RequestParam(name = "pageNum", defaultValue = "0") int pageNum,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {

        if (draftId == null || draftId.isBlank()) {
            return ApiResponse.error(400, "draftId required");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        int safePage = Math.max(0, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        // Enforce tenant scoping at the draft layer first — a foreign draft id
        // must surface as 404, not leak.
        Long owns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_skill_draft WHERE pid = ? AND tenant_id = ?",
                Long.class, draftId, tenantId);
        if (owns == null || owns == 0L) {
            return ApiResponse.error(404, "draft_not_found");
        }

        String sql = "SELECT pid, draft_id, original_run_id, " +
                "       shadow_status, shadow_duration_ms, shadow_cost_usd, " +
                "       shadow_tokens, shadow_output_hash, " +
                "       original_status, original_duration_ms, original_cost_usd, " +
                "       original_output_hash, " +
                "       output_match, fidelity_match, output_diff::text AS output_diff, " +
                "       created_at " +
                "  FROM ab_agent_shadow_run " +
                " WHERE tenant_id = ? AND draft_id = ? " +
                " ORDER BY created_at DESC " +
                " LIMIT ? OFFSET ?";

        List<ShadowRunListItem> rows = jdbcTemplate.query(
                sql, ROW_MAPPER, tenantId, draftId, safeSize, (long) safePage * safeSize);
        return ApiResponse.ok(rows);
    }

    // =========================================================================
    // GET /{shadowRunPid} — single row detail
    // =========================================================================

    /**
     * {@code GET /api/admin/shadow-runs/{shadowRunPid}}
     *
     * <p>Returns 404 when the row does not exist in the caller's tenant.
     * The {@code outputDiff} JSONB is serialised to text; the UI handles
     * pretty-printing.
     */
    @GetMapping("/{shadowRunPid}")
    public ApiResponse<ShadowRunListItem> detail(@PathVariable("shadowRunPid") String shadowRunPid) {
        if (shadowRunPid == null || shadowRunPid.isBlank()) {
            return ApiResponse.error(400, "shadowRunPid required");
        }
        Long tenantId = MetaContext.getCurrentTenantId();

        String sql = "SELECT pid, draft_id, original_run_id, " +
                "       shadow_status, shadow_duration_ms, shadow_cost_usd, " +
                "       shadow_tokens, shadow_output_hash, " +
                "       original_status, original_duration_ms, original_cost_usd, " +
                "       original_output_hash, " +
                "       output_match, fidelity_match, output_diff::text AS output_diff, " +
                "       created_at " +
                "  FROM ab_agent_shadow_run " +
                " WHERE tenant_id = ? AND pid = ? " +
                " LIMIT 1";
        List<ShadowRunListItem> rows = jdbcTemplate.query(sql, ROW_MAPPER, tenantId, shadowRunPid);
        if (rows.isEmpty()) {
            return ApiResponse.error(404, "shadow_run_not_found");
        }
        return ApiResponse.ok(rows.get(0));
    }

    // =========================================================================
    // RowMappers
    // =========================================================================

    private static final RowMapper<ShadowRunAggregation> AGGREGATION_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp latest = rs.getTimestamp("latest_at");
        BigDecimal fidelityRate = rs.getBigDecimal("fidelity_match_rate");
        BigDecimal outputRate = rs.getBigDecimal("output_match_rate");
        BigDecimal costDelta = rs.getBigDecimal("cost_delta");
        return ShadowRunAggregation.builder()
                .draftId(rs.getString("draft_id"))
                .draftSkillCode(rs.getString("draft_skill_code"))
                .draftStatus(rs.getString("draft_status"))
                .runCount(rs.getLong("run_count"))
                .fidelitySamples(rs.getLong("fidelity_samples"))
                .outputSamples(rs.getLong("output_samples"))
                .fidelityMatchRate(fidelityRate == null ? null : fidelityRate.doubleValue())
                .outputMatchRate(outputRate == null ? null : outputRate.doubleValue())
                .costDelta(costDelta)
                .latestAt(latest == null ? null : latest.toInstant())
                .build();
    };

    private static final RowMapper<ShadowRunListItem> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return ShadowRunListItem.builder()
                .pid(rs.getString("pid"))
                .draftId(rs.getString("draft_id"))
                .originalRunId(rs.getString("original_run_id"))
                .shadowStatus(rs.getString("shadow_status"))
                .shadowDurationMs(getLong(rs, "shadow_duration_ms"))
                .shadowCostUsd(rs.getBigDecimal("shadow_cost_usd"))
                .shadowTokens(getInteger(rs, "shadow_tokens"))
                .shadowOutputHash(rs.getString("shadow_output_hash"))
                .originalStatus(rs.getString("original_status"))
                .originalDurationMs(getLong(rs, "original_duration_ms"))
                .originalCostUsd(rs.getBigDecimal("original_cost_usd"))
                .originalOutputHash(rs.getString("original_output_hash"))
                .outputMatch((Boolean) rs.getObject("output_match"))
                .fidelityMatch((Boolean) rs.getObject("fidelity_match"))
                .outputDiff(rs.getString("output_diff"))
                .createdAt(createdAt == null ? null : createdAt.toInstant())
                .build();
    };

    private static Integer getInteger(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Long getLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
