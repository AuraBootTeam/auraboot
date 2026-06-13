package com.auraboot.framework.agent.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime operations diagnostics for the replay UI —
 * {@code GET /api/admin/agent-runs/runtime-ops}.
 *
 * <p>Split out of {@link AgentRunController} (see its class javadoc for the
 * authorisation, tenant-isolation and read-only contract that applies to the
 * whole {@code /api/admin/agent-runs} surface). URLs are unchanged.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/agent-runs")
@RequirePermission(MetaPermission.ACP_AGENT_RUN_ADMIN)
public class AgentRunOpsController {

    /** Hard cap on runtime ops diagnostic rows per section. */
    private static final int MAX_RUNTIME_OPS_ROWS = 200;

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public AgentRunOpsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // =========================================================================
    // GET /runtime-ops — pending/approval/durable execution diagnostics
    // =========================================================================

    /**
     * {@code GET /api/admin/agent-runs/runtime-ops}
     *
     * <p>Read-only operational projection for pending confirmations, approval
     * requests, durable side-effect execution ledger rows, and workflow
     * checkpoints. It intentionally does not retry, compensate, approve, or
     * mutate any runtime state.
     */
    @GetMapping("/runtime-ops")
    public ApiResponse<Map<String, Object>> runtimeOps(
            @RequestParam(required = false) String runId,
            @RequestParam(defaultValue = "50") int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String normalizedRunId = AgentRunQuerySupport.normalizeBlank(runId);
        int safeLimit = Math.min(Math.max(1, limit), MAX_RUNTIME_OPS_ROWS);
        List<Map<String, Object>> approvals = loadRuntimeApprovals(tenantId, normalizedRunId, safeLimit);
        List<Map<String, Object>> pendingToolExecutions = loadRuntimeIdempotencyRows(
                tenantId,
                "agent.pending_tool_execution:%",
                null,
                safeLimit);
        List<Map<String, Object>> durableToolExecutions = loadRuntimeIdempotencyRows(
                tenantId,
                "agent.tool_execution:%",
                normalizedRunId,
                safeLimit);
        List<Map<String, Object>> checkpoints = loadRuntimeCheckpoints(tenantId, normalizedRunId, safeLimit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", normalizedRunId);
        result.put("limit", safeLimit);
        result.put("summary", runtimeOpsSummary(approvals, pendingToolExecutions, durableToolExecutions, checkpoints));
        result.put("approvals", approvals);
        result.put("pendingToolExecutions", pendingToolExecutions);
        result.put("pendingToolExecutionScope", "tenant");
        result.put("durableToolExecutions", durableToolExecutions);
        result.put("checkpoints", checkpoints);
        return ApiResponse.ok(result);
    }

    // =========================================================================
    // Private loaders
    // =========================================================================

    private List<Map<String, Object>> loadRuntimeApprovals(Long tenantId, String runId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT pid, run_id, task_id, approval_type, approval_status,
                       policy_id, idempotency_key, expires_at, created_at, approved_at
                  FROM ab_agent_approval
                 WHERE tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (runId != null) {
            sql.append(" AND run_id = ?");
            args.add(runId);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pid", rs.getString("pid"));
            row.put("runId", rs.getString("run_id"));
            row.put("taskId", rs.getString("task_id"));
            row.put("approvalType", rs.getString("approval_type"));
            row.put("approvalStatus", rs.getString("approval_status"));
            row.put("policyId", rs.getString("policy_id"));
            row.put("idempotencyKey", rs.getString("idempotency_key"));
            row.put("expiresAt", timestampString(rs, "expires_at"));
            row.put("createdAt", timestampString(rs, "created_at"));
            row.put("approvedAt", timestampString(rs, "approved_at"));
            return row;
        }, args.toArray());
    }

    private Map<String, Object> runtimeOpsSummary(List<Map<String, Object>> approvals,
                                                  List<Map<String, Object>> pendingToolExecutions,
                                                  List<Map<String, Object>> durableToolExecutions,
                                                  List<Map<String, Object>> checkpoints) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("approvalPending", countByStatus(approvals, "approvalStatus", "pending"));
        summary.put("approvalTerminal", countMatching(approvals, row ->
                isStatus(row.get("approvalStatus"), "approved")
                        || isStatus(row.get("approvalStatus"), "rejected")
                        || isStatus(row.get("approvalStatus"), "expired")));
        summary.put("pendingToolRunning", countByStatus(pendingToolExecutions, "status", "RUNNING"));
        summary.put("pendingToolSucceeded", countByStatus(pendingToolExecutions, "status", "SUCCEEDED"));
        summary.put("pendingToolFailed", countByStatus(pendingToolExecutions, "status", "FAILED"));
        summary.put("durableRunning", countByStatus(durableToolExecutions, "status", "RUNNING"));
        summary.put("durableSucceeded", countByStatus(durableToolExecutions, "status", "SUCCEEDED"));
        summary.put("durableFailed", countByStatus(durableToolExecutions, "status", "FAILED"));
        summary.put("durableCompensationRequired", countByStatus(
                durableToolExecutions, "status", "COMPENSATION_REQUIRED"));
        summary.put("durableCompensated", countByStatus(durableToolExecutions, "status", "COMPENSATED"));
        summary.put("checkpointCount", checkpoints == null ? 0 : checkpoints.size());
        return summary;
    }

    private int countByStatus(List<Map<String, Object>> rows, String key, String status) {
        return countMatching(rows, row -> isStatus(row.get(key), status));
    }

    private int countMatching(List<Map<String, Object>> rows,
                              java.util.function.Predicate<Map<String, Object>> predicate) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Map<String, Object> row : rows) {
            if (row != null && predicate.test(row)) {
                count++;
            }
        }
        return count;
    }

    private boolean isStatus(Object actual, String expected) {
        return actual != null && expected != null
                && expected.equalsIgnoreCase(String.valueOf(actual));
    }

    private List<Map<String, Object>> loadRuntimeIdempotencyRows(Long tenantId,
                                                                 String commandCodePattern,
                                                                 String runId,
                                                                 int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT client_request_id, request_hash, command_code, status,
                       outcome::text AS outcome, expires_at, created_at
                  FROM ab_idempotency_record
                 WHERE tenant_id = ?
                   AND command_code LIKE ?
                   AND expires_at > NOW()
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(commandCodePattern);
        if (runId != null) {
            sql.append(" AND outcome->'request'->>'runPid' = ?");
            args.add(runId);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("clientRequestId", rs.getString("client_request_id"));
            row.put("requestHash", rs.getString("request_hash"));
            row.put("commandCode", rs.getString("command_code"));
            row.put("status", rs.getString("status"));
            row.put("outcome", rs.getString("outcome"));
            row.put("expiresAt", timestampString(rs, "expires_at"));
            row.put("createdAt", timestampString(rs, "created_at"));
            return row;
        }, args.toArray());
    }

    private List<Map<String, Object>> loadRuntimeCheckpoints(Long tenantId, String runId, int limit) {
        if (runId == null) {
            return List.of();
        }
        String sql = """
                SELECT pid, run_pid, checkpoint_type, step_index, reason,
                       plan_snapshot::text AS plan_snapshot,
                       state_snapshot::text AS state_snapshot,
                       created_at
                  FROM ab_agent_run_checkpoint
                 WHERE tenant_id = ?
                   AND run_pid = ?
                 ORDER BY created_at DESC
                 LIMIT ?
                """;
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pid", rs.getString("pid"));
                row.put("runPid", rs.getString("run_pid"));
                row.put("checkpointType", rs.getString("checkpoint_type"));
                row.put("stepIndex", AgentRunQuerySupport.getInteger(rs, "step_index"));
                row.put("reason", rs.getString("reason"));
                row.put("planSnapshot", rs.getString("plan_snapshot"));
                row.put("stateSnapshot", rs.getString("state_snapshot"));
                row.put("createdAt", timestampString(rs, "created_at"));
                return row;
            }, tenantId, runId, limit);
        } catch (DataAccessException e) {
            log.debug("Agent workflow checkpoint diagnostics unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    private static String timestampString(ResultSet rs, String col) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(col);
        return timestamp == null ? null : timestamp.toInstant().toString();
    }
}
