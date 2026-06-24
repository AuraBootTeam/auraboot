package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.dto.replay.AgentActionItem;
import com.auraboot.framework.agent.dto.replay.AgentApprovalAuditItem;
import com.auraboot.framework.agent.dto.replay.AgentAuthorizationDecisionItem;
import com.auraboot.framework.agent.dto.replay.AgentResultContractItem;
import com.auraboot.framework.agent.dto.replay.AgentRunListItem;
import com.auraboot.framework.agent.dto.replay.AgentRuntimeAuditTrail;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime evidence bundle for the replay UI — {@code GET /api/admin/agent-runs/audit}.
 *
 * <p>Split out of {@link AgentRunController} (see its class javadoc for the
 * authorisation, tenant-isolation and read-only contract that applies to the
 * whole {@code /api/admin/agent-runs} surface). URLs are unchanged.
 */
@RestController
@RequestMapping("/api/admin/agent-runs")
@RequirePermission(MetaPermission.ACP_AGENT_RUN_ADMIN)
public class AgentRunAuditController {

    private final JdbcTemplate jdbcTemplate;
    private final AgentRunQuerySupport querySupport;

    @Autowired
    public AgentRunAuditController(JdbcTemplate jdbcTemplate, AgentRunQuerySupport querySupport) {
        this.jdbcTemplate = jdbcTemplate;
        this.querySupport = querySupport;
    }

    // =========================================================================
    // GET /audit — runtime evidence bundle
    // =========================================================================

    /**
     * {@code GET /api/admin/agent-runs/audit}
     *
     * <p>Read-only incident triage projection over the existing runtime audit
     * tables. The endpoint intentionally does not mutate, retry, or repair any
     * agent state; it only collects evidence by tenant-scoped run/conversation/tool.
     */
    @GetMapping("/audit")
    public ApiResponse<AgentRuntimeAuditTrail> audit(
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) String toolName) {
        if ((runId == null || runId.isBlank()) && conversationId == null) {
            return ApiResponse.error(400, "runId_or_conversationId_required");
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        String resolvedRunId = AgentRunQuerySupport.normalizeBlank(runId);
        if (resolvedRunId == null && conversationId != null) {
            resolvedRunId = resolveRunIdByConversation(tenantId, conversationId);
        }
        if (resolvedRunId == null) {
            return ApiResponse.error(404, "agent_run_not_found");
        }

        AgentRunListItem run = querySupport.loadRun(tenantId, resolvedRunId);
        if (run == null) {
            return ApiResponse.error(404, "agent_run_not_found");
        }

        String normalizedTool = AgentRunQuerySupport.normalizeBlank(toolName);
        List<AgentActionItem> actions = querySupport.loadActions(tenantId, resolvedRunId).stream()
                .filter(action -> toolMatches(action, normalizedTool))
                .toList();
        List<AgentResultContractItem> resultContracts = querySupport.buildResultContracts(actions);
        List<AgentAuthorizationDecisionItem> authorizationDecisions =
                loadAuthorizationDecisions(tenantId, resolvedRunId, normalizedTool);
        List<AgentApprovalAuditItem> approvals =
                loadApprovals(tenantId, resolvedRunId, normalizedTool, approvalIds(authorizationDecisions));

        return ApiResponse.ok(AgentRuntimeAuditTrail.builder()
                .runId(resolvedRunId)
                .conversationId(conversationId)
                .toolName(normalizedTool)
                .actions(actions)
                .authorizationDecisions(authorizationDecisions)
                .approvals(approvals)
                .resultContracts(resultContracts)
                .build());
    }

    // =========================================================================
    // Private loaders
    // =========================================================================

    private String resolveRunIdByConversation(Long tenantId, Long conversationId) {
        String sql = "SELECT r.pid AS run_id, r.metadata, t.input_data " +
                "  FROM ab_agent_run r " +
                "  LEFT JOIN ab_agent_task t ON t.tenant_id = r.tenant_id AND t.pid = r.task_id " +
                " WHERE r.tenant_id = ? " +
                " ORDER BY r.created_at DESC " +
                " LIMIT 500";
        List<RunConversationSeed> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new RunConversationSeed(
                rs.getString("run_id"),
                rs.getString("metadata"),
                rs.getString("input_data")), tenantId);
        for (RunConversationSeed row : rows) {
            JsonNode metadata = querySupport.parseJsonObject(row.metadata());
            JsonNode input = querySupport.parseJsonObject(row.inputData());
            Long rowConversationId = AgentRunQuerySupport.firstLong(input, "conversationId", metadata, "conversationId");
            if (conversationId.equals(rowConversationId)) {
                return row.runId();
            }
        }
        return null;
    }

    private List<AgentAuthorizationDecisionItem> loadAuthorizationDecisions(
            Long tenantId, String runId, String toolName) {
        StringBuilder sql = new StringBuilder("SELECT pid, run_id, decision_kind, tool_ref, skill_code, " +
                "       blast_radius, requested_effects::text AS requested_effects, " +
                "       granted_effects::text AS granted_effects, " +
                "       rejected_effects::text AS rejected_effects, " +
                "       require_approval, approval_id, policy_id, policy_version, " +
                "       decision_reason, decision_at " +
                "  FROM ab_agent_authorization_decision " +
                " WHERE tenant_id = ? AND run_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(runId);
        if (toolName != null) {
            sql.append(" AND (tool_ref = ? OR skill_code = ?)");
            args.add(toolName);
            args.add(toolName);
        }
        sql.append(" ORDER BY decision_at ASC");
        return jdbcTemplate.query(sql.toString(), AUTHORIZATION_DECISION_ROW_MAPPER, args.toArray());
    }

    private List<AgentApprovalAuditItem> loadApprovals(
            Long tenantId, String runId, String toolName, List<String> approvalIds) {
        StringBuilder sql = new StringBuilder("SELECT pid, run_id, approval_type, approval_title, " +
                "       approval_description, request_data, approval_status, policy_id, " +
                "       approver_id, created_at, approved_at " +
                "  FROM ab_agent_approval " +
                " WHERE tenant_id = ? AND run_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(runId);
        boolean hasToolFilter = toolName != null;
        boolean hasLinkedApprovals = approvalIds != null && !approvalIds.isEmpty();
        if (hasToolFilter || hasLinkedApprovals) {
            sql.append(" AND (");
        }
        if (hasToolFilter) {
            String like = "%" + toolName.toLowerCase() + "%";
            sql.append("(LOWER(COALESCE(approval_title, '')) LIKE ? " +
                    "OR LOWER(COALESCE(approval_description, '')) LIKE ? " +
                    "OR LOWER(COALESCE(request_data, '')) LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (hasLinkedApprovals) {
            if (hasToolFilter) {
                sql.append(" OR ");
            }
            sql.append("pid IN (");
            for (int i = 0; i < approvalIds.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
                args.add(approvalIds.get(i));
            }
            sql.append(")");
        }
        if (hasToolFilter || hasLinkedApprovals) {
            sql.append(")");
        }
        sql.append(" ORDER BY created_at ASC");
        return jdbcTemplate.query(sql.toString(), this::mapApprovalAuditRow, args.toArray());
    }

    private static boolean toolMatches(AgentActionItem action, String toolName) {
        if (toolName == null) {
            return true;
        }
        return equalsTrimmed(toolName, action.getActionCode())
                || equalsTrimmed(toolName, action.getCommandCode())
                || equalsTrimmed(toolName, action.getSkillCode())
                || equalsTrimmed(toolName, action.getResultContractId());
    }

    private static boolean equalsTrimmed(String expected, String actual) {
        return expected != null && actual != null && expected.equals(actual.trim());
    }

    private static List<String> approvalIds(List<AgentAuthorizationDecisionItem> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return List.of();
        }
        return decisions.stream()
                .map(AgentAuthorizationDecisionItem::getApprovalId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    private AgentApprovalAuditItem mapApprovalAuditRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        String requestData = rs.getString("request_data");
        return AgentApprovalAuditItem.builder()
                .pid(rs.getString("pid"))
                .runId(rs.getString("run_id"))
                .approvalType(rs.getString("approval_type"))
                .approvalTitle(rs.getString("approval_title"))
                .approvalDescription(rs.getString("approval_description"))
                .requestData(requestData)
                .targetPid(extractTargetPidFromApprovalData(requestData))
                .approvalStatus(rs.getString("approval_status"))
                .policyId(rs.getString("policy_id"))
                .approverId(getLong(rs, "approver_id"))
                .createdAt(createdAt == null ? null : createdAt.toInstant())
                .approvedAt(approvedAt == null ? null : approvedAt.toInstant())
                .build();
    }

    private String extractTargetPidFromApprovalData(String requestData) {
        JsonNode node = querySupport.parseJsonObject(requestData);
        return extractTargetPid(node);
    }

    private String extractTargetPid(JsonNode node) {
        if (node == null) {
            return null;
        }
        String direct = AgentRunQuerySupport.firstNonBlank(
                AgentRunQuerySupport.text(node, "targetPid"),
                AgentRunQuerySupport.text(node, "targetRecordPid"),
                AgentRunQuerySupport.text(node, "recordPid"),
                AgentRunQuerySupport.text(node, "pid"));
        if (direct != null) {
            return direct;
        }
        for (String container : List.of("args", "input", "payload", "parameters")) {
            JsonNode nested = node.get(container);
            if (nested != null && nested.isObject()) {
                String nestedPid = extractTargetPid(nested);
                if (nestedPid != null) {
                    return nestedPid;
                }
            }
        }
        return null;
    }

    // =========================================================================
    // RowMappers
    // =========================================================================

    private static final RowMapper<AgentAuthorizationDecisionItem> AUTHORIZATION_DECISION_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp decisionAt = rs.getTimestamp("decision_at");
        return AgentAuthorizationDecisionItem.builder()
                .pid(rs.getString("pid"))
                .runId(rs.getString("run_id"))
                .decisionKind(rs.getString("decision_kind"))
                .toolRef(rs.getString("tool_ref"))
                .skillCode(rs.getString("skill_code"))
                .blastRadius(rs.getString("blast_radius"))
                .requestedEffects(rs.getString("requested_effects"))
                .grantedEffects(rs.getString("granted_effects"))
                .rejectedEffects(rs.getString("rejected_effects"))
                .requireApproval(rs.getBoolean("require_approval"))
                .approvalId(rs.getString("approval_id"))
                .policyId(rs.getString("policy_id"))
                .policyVersion(AgentRunQuerySupport.getInteger(rs, "policy_version"))
                .decisionReason(rs.getString("decision_reason"))
                .decisionAt(decisionAt == null ? null : decisionAt.toInstant())
                .build();
    };

    private static Long getLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private record RunConversationSeed(String runId, String metadata, String inputData) {}
}
