package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.dto.replay.AgentActionItem;
import com.auraboot.framework.agent.dto.replay.AgentBifSummary;
import com.auraboot.framework.agent.dto.replay.AgentInterruptItem;
import com.auraboot.framework.agent.dto.replay.AgentRunDetail;
import com.auraboot.framework.agent.dto.replay.AgentRunListItem;
import com.auraboot.framework.agent.dto.replay.AgentRunPage;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Replay UI MVP — admin-facing read-only listing of agent runs.
 *
 * <p>Surface for operators to inspect the existing audit trail in
 * {@code ab_agent_run} + {@code ab_agent_action} + {@code ab_agent_interrupt_log}
 * + {@code ab_agent_bif} without having to {@code psql} into the database. This
 * is the OSS counterpart of LangGraph's "Trace UI": all data is already
 * persisted, the controller is purely a projection.
 *
 * <p><b>Authorisation.</b> Lives under {@code /api/admin/**} so the platform
 * {@link com.auraboot.framework.application.security.AdminRoleInterceptor}
 * already enforces tenant-admin role before any handler executes. No per-handler
 * guard is required — see PR-85 design doc §9.2 for the rationale.
 *
 * <p><b>Tenant isolation.</b> The interceptor authenticates the role but does
 * <em>not</em> auto-attach a {@code tenant_id} predicate the way the
 * MyBatis dynamic-table interceptor does for ORM mappers. Because every query
 * here runs through {@link JdbcTemplate} we manually append
 * {@code WHERE tenant_id = ?} (sourced from {@link MetaContext}) on every
 * statement. Cross-tenant leakage is covered by the
 * {@code tenant_isolation_otherTenantRunInvisible} integration test.
 *
 * <p><b>Read-only contract.</b> No write endpoints, no time-travel, no
 * fork-from-step, no replay buttons. Those are deferred to P2. The controller
 * never mutates {@code ab_agent_*} tables.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/agent-runs")
public class AgentRunController {

    /** Hard cap on page size to keep payload + admin SQL bounded. */
    private static final int MAX_PAGE_SIZE = 200;

    /** Hard cap on returned actions per run — interactive review only. */
    private static final int MAX_ACTIONS = 1000;

    /** Hard cap on returned interrupts per run. */
    private static final int MAX_INTERRUPTS = 200;

    /** Hard cap on returned child runs per run. */
    private static final int MAX_CHILD_RUNS = 200;

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public AgentRunController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // =========================================================================
    // GET / — paginated list
    // =========================================================================

    /**
     * {@code GET /api/admin/agent-runs}
     *
     * @param page          0-based page index, clamped to ≥ 0.
     * @param size          page size, clamped to {@code [1, MAX_PAGE_SIZE]}.
     * @param status        optional {@code run_status} filter (running/succeeded/failed/cancelled).
     * @param agentCode     optional {@code agent_id} filter (exact match).
     * @param parentRunId   optional {@code parent_run_id} filter — pass a parent's pid
     *                      to fetch only its child runs.
     * @param keyword       optional case-insensitive substring match against
     *                      {@code pid} / {@code agent_id} / {@code task_id}.
     */
    @GetMapping
    public ApiResponse<AgentRunPage> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String agentCode,
            @RequestParam(required = false) String parentRunId,
            @RequestParam(required = false) String keyword) {

        Long tenantId = MetaContext.getCurrentTenantId();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        StringBuilder where = new StringBuilder("WHERE r.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);

        if (status != null && !status.isBlank()) {
            where.append(" AND r.run_status = ?");
            args.add(status.trim());
        }
        if (agentCode != null && !agentCode.isBlank()) {
            where.append(" AND r.agent_id = ?");
            args.add(agentCode.trim());
        }
        if (parentRunId != null && !parentRunId.isBlank()) {
            where.append(" AND r.parent_run_id = ?");
            args.add(parentRunId.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim().toLowerCase() + "%";
            where.append(" AND (LOWER(r.pid) LIKE ? OR LOWER(r.agent_id) LIKE ? OR LOWER(r.task_id) LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }

        // total count first — cheap, makes the empty-page case trivially observable.
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run r " + where,
                Long.class, args.toArray());
        long totalCount = total == null ? 0L : total;

        if (totalCount == 0L) {
            return ApiResponse.ok(AgentRunPage.builder()
                    .items(Collections.emptyList())
                    .total(0L)
                    .page(safePage)
                    .size(safeSize)
                    .build());
        }

        // Joined SELECT — LEFT JOIN BIF so runs without grounding still show up.
        // intent_summary uses MIN(intent) to deduplicate when a run has multiple
        // BIF rows (multi-turn grounding) — first-by-min is stable for display.
        String sql = "SELECT r.pid, r.agent_id, r.run_status, r.parent_run_id, " +
                "       r.subtask_origin, r.total_cost, r.duration_ms, " +
                "       r.created_at, r.completed_at, " +
                "       (SELECT b.intent FROM ab_agent_bif b " +
                "         WHERE b.run_id = r.pid AND b.tenant_id = r.tenant_id " +
                "         ORDER BY b.created_at ASC LIMIT 1) AS intent_summary " +
                "  FROM ab_agent_run r " +
                where +
                " ORDER BY r.created_at DESC " +
                " LIMIT ? OFFSET ?";

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);

        List<AgentRunListItem> items = jdbcTemplate.query(sql, RUN_ROW_MAPPER, pageArgs.toArray());

        return ApiResponse.ok(AgentRunPage.builder()
                .items(items)
                .total(totalCount)
                .page(safePage)
                .size(safeSize)
                .build());
    }

    // =========================================================================
    // GET /{runId} — single-run detail
    // =========================================================================

    /**
     * {@code GET /api/admin/agent-runs/{runId}}
     *
     * <p>Returns the run row plus its action timeline, interrupt log, child
     * runs, and BIF summary. Returns {@code code=404} when the run does not
     * exist in the caller's tenant — cross-tenant ids look identical to
     * not-found, deliberately.
     */
    @GetMapping("/{runId}")
    public ApiResponse<AgentRunDetail> detail(@PathVariable("runId") String runId) {
        if (runId == null || runId.isBlank()) {
            return ApiResponse.error(400, "runId required");
        }
        Long tenantId = MetaContext.getCurrentTenantId();

        AgentRunListItem run = loadRun(tenantId, runId);
        if (run == null) {
            return ApiResponse.error(404, "agent_run_not_found");
        }

        List<AgentActionItem> actions = loadActions(tenantId, runId);
        List<AgentInterruptItem> interrupts = loadInterrupts(tenantId, runId);
        List<AgentRunListItem> children = loadChildRuns(tenantId, runId);
        AgentBifSummary bif = loadBif(tenantId, runId);

        AgentRunDetail detail = AgentRunDetail.builder()
                .run(run)
                .actions(actions)
                .interruptLog(interrupts)
                .childRuns(children)
                .bif(bif)
                .build();
        return ApiResponse.ok(detail);
    }

    // =========================================================================
    // Private loaders
    // =========================================================================

    private AgentRunListItem loadRun(Long tenantId, String runId) {
        String sql = "SELECT r.pid, r.agent_id, r.run_status, r.parent_run_id, " +
                "       r.subtask_origin, r.total_cost, r.duration_ms, " +
                "       r.created_at, r.completed_at, " +
                "       (SELECT b.intent FROM ab_agent_bif b " +
                "         WHERE b.run_id = r.pid AND b.tenant_id = r.tenant_id " +
                "         ORDER BY b.created_at ASC LIMIT 1) AS intent_summary " +
                "  FROM ab_agent_run r " +
                " WHERE r.tenant_id = ? AND r.pid = ? " +
                " LIMIT 1";
        List<AgentRunListItem> rows = jdbcTemplate.query(sql, RUN_ROW_MAPPER, tenantId, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<AgentActionItem> loadActions(Long tenantId, String runId) {
        String sql = "SELECT pid, step_index, tool_call_index, action_code, action_type, " +
                "       intent_summary, target_model, target_record_id, " +
                "       before_snapshot::text AS before_snapshot, " +
                "       after_snapshot::text  AS after_snapshot, " +
                "       field_changes::text   AS field_changes, " +
                "       command_code, command_result, " +
                "       risk_level, estimated_risk, risk_deviation, reversal_mode, " +
                "       action_status, error_message, cost_usd, token_usage, " +
                "       fidelity, skill_code, parallel_group_id, parallel_index, executed_at " +
                "  FROM ab_agent_action " +
                " WHERE tenant_id = ? AND run_id = ? " +
                " ORDER BY executed_at ASC, step_index ASC NULLS LAST, " +
                "          parallel_index ASC NULLS LAST " +
                " LIMIT ?";
        return jdbcTemplate.query(sql, ACTION_ROW_MAPPER, tenantId, runId, MAX_ACTIONS);
    }

    private List<AgentInterruptItem> loadInterrupts(Long tenantId, String runId) {
        // Surface both directions: interrupts that hit this run AND interrupts
        // whose subtask spawn produced this run (so child runs see why they
        // exist when an operator drills in from the spawn tree).
        String sql = "SELECT pid, session_id, active_run_id, new_message_excerpt, " +
                "       sub_policy, classifier_tier, confidence, reason, action_taken, " +
                "       subtask_run_id, created_at " +
                "  FROM ab_agent_interrupt_log " +
                " WHERE tenant_id = ? " +
                "   AND (active_run_id = ? OR subtask_run_id = ?) " +
                " ORDER BY created_at ASC " +
                " LIMIT ?";
        return jdbcTemplate.query(sql, INTERRUPT_ROW_MAPPER,
                tenantId, runId, runId, MAX_INTERRUPTS);
    }

    private List<AgentRunListItem> loadChildRuns(Long tenantId, String runId) {
        String sql = "SELECT r.pid, r.agent_id, r.run_status, r.parent_run_id, " +
                "       r.subtask_origin, r.total_cost, r.duration_ms, " +
                "       r.created_at, r.completed_at, " +
                "       (SELECT b.intent FROM ab_agent_bif b " +
                "         WHERE b.run_id = r.pid AND b.tenant_id = r.tenant_id " +
                "         ORDER BY b.created_at ASC LIMIT 1) AS intent_summary " +
                "  FROM ab_agent_run r " +
                " WHERE r.tenant_id = ? AND r.parent_run_id = ? " +
                " ORDER BY r.created_at ASC " +
                " LIMIT ?";
        return jdbcTemplate.query(sql, RUN_ROW_MAPPER, tenantId, runId, MAX_CHILD_RUNS);
    }

    private AgentBifSummary loadBif(Long tenantId, String runId) {
        String sql = "SELECT pid, intent, primary_object, " +
                "       confidence::text AS confidence, dispatched_skill, channel " +
                "  FROM ab_agent_bif " +
                " WHERE tenant_id = ? AND run_id = ? " +
                " ORDER BY created_at ASC " +
                " LIMIT 1";
        List<AgentBifSummary> rows = jdbcTemplate.query(sql, BIF_ROW_MAPPER, tenantId, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // =========================================================================
    // RowMappers
    // =========================================================================

    private static final RowMapper<AgentRunListItem> RUN_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");

        // duration_ms preferred (set explicitly when run terminates); when it's
        // NULL but completed_at is set, derive from the timestamp delta.
        Long storedDuration = (Long) rs.getObject("duration_ms");
        long durationMs;
        if (storedDuration != null) {
            durationMs = storedDuration;
        } else if (completedAt != null && createdAt != null) {
            durationMs = Duration.between(createdAt.toInstant(), completedAt.toInstant()).toMillis();
        } else {
            durationMs = 0L;
        }

        return AgentRunListItem.builder()
                .runId(rs.getString("pid"))
                .agentCode(rs.getString("agent_id"))
                .runStatus(rs.getString("run_status"))
                .parentRunId(rs.getString("parent_run_id"))
                .subtaskOrigin(rs.getString("subtask_origin"))
                .costUsd(getBigDecimal(rs, "total_cost"))
                .durationMs(durationMs)
                .createdAt(createdAt == null ? null : createdAt.toInstant())
                .completedAt(completedAt == null ? null : completedAt.toInstant())
                .intentSummary(rs.getString("intent_summary"))
                .build();
    };

    private static final RowMapper<AgentActionItem> ACTION_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp executedAt = rs.getTimestamp("executed_at");
        Boolean riskDeviation = (Boolean) rs.getObject("risk_deviation");
        return AgentActionItem.builder()
                .pid(rs.getString("pid"))
                .stepIndex(getInteger(rs, "step_index"))
                .toolCallIndex(getInteger(rs, "tool_call_index"))
                .actionCode(rs.getString("action_code"))
                .actionType(rs.getString("action_type"))
                .intentSummary(rs.getString("intent_summary"))
                .targetModel(rs.getString("target_model"))
                .targetRecordId(rs.getString("target_record_id"))
                .beforeSnapshot(rs.getString("before_snapshot"))
                .afterSnapshot(rs.getString("after_snapshot"))
                .fieldChanges(rs.getString("field_changes"))
                .commandCode(rs.getString("command_code"))
                .commandResult(rs.getString("command_result"))
                .riskLevel(rs.getString("risk_level"))
                .estimatedRisk(rs.getString("estimated_risk"))
                .riskDeviation(riskDeviation)
                .reversalMode(rs.getString("reversal_mode"))
                .actionStatus(rs.getString("action_status"))
                .errorMessage(rs.getString("error_message"))
                .costUsd(getBigDecimal(rs, "cost_usd"))
                .tokenUsage(getInteger(rs, "token_usage"))
                .fidelity(rs.getString("fidelity"))
                .skillCode(rs.getString("skill_code"))
                .parallelGroupId(rs.getString("parallel_group_id"))
                .parallelIndex(getInteger(rs, "parallel_index"))
                .executedAt(executedAt == null ? null : executedAt.toInstant())
                .build();
    };

    private static final RowMapper<AgentInterruptItem> INTERRUPT_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return AgentInterruptItem.builder()
                .pid(rs.getString("pid"))
                .sessionId(rs.getString("session_id"))
                .activeRunId(rs.getString("active_run_id"))
                .newMessageExcerpt(rs.getString("new_message_excerpt"))
                .subPolicy(rs.getString("sub_policy"))
                .classifierTier(rs.getString("classifier_tier"))
                .confidence(getBigDecimal(rs, "confidence"))
                .reason(rs.getString("reason"))
                .actionTaken(rs.getString("action_taken"))
                .subtaskRunId(rs.getString("subtask_run_id"))
                .createdAt(createdAt == null ? null : createdAt.toInstant())
                .build();
    };

    private static final RowMapper<AgentBifSummary> BIF_ROW_MAPPER = (rs, rowNum) ->
            AgentBifSummary.builder()
                    .pid(rs.getString("pid"))
                    .intent(rs.getString("intent"))
                    .primaryObject(rs.getString("primary_object"))
                    .confidence(rs.getString("confidence"))
                    .dispatchedSkill(rs.getString("dispatched_skill"))
                    .channel(rs.getString("channel"))
                    .build();

    private static Integer getInteger(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static BigDecimal getBigDecimal(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return rs.wasNull() ? null : v;
    }
}
