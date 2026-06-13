package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.dto.replay.AgentActionItem;
import com.auraboot.framework.agent.dto.replay.AgentResultContractItem;
import com.auraboot.framework.agent.dto.replay.AgentRunListItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared read-only query helpers for the {@code /api/admin/agent-runs} replay
 * surface. Extracted from {@link AgentRunController} so that
 * {@link AgentRunController}, {@link AgentRunAuditController} and
 * {@link AgentRunOpsController} reuse the exact same run/action loaders, row
 * mappers and result-contract reconstruction without duplicating logic.
 */
@Slf4j
@Component
class AgentRunQuerySupport {

    /** Hard cap on returned actions per run — interactive review only. */
    static final int MAX_ACTIONS = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    AgentRunQuerySupport(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    AgentRunListItem loadRun(Long tenantId, String runId) {
        String sql = "SELECT r.pid, r.agent_id, r.run_status, r.parent_run_id, " +
                "       r.subtask_origin, r.total_cost, " +
                "       r.child_aggregate_cost, r.child_aggregate_tokens, " +
                "       r.duration_ms, " +
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

    List<AgentActionItem> loadActions(Long tenantId, String runId) {
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

    List<AgentResultContractItem> buildResultContracts(List<AgentActionItem> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        return actions.stream()
                .map(this::buildResultContract)
                .toList();
    }

    private AgentResultContractItem buildResultContract(AgentActionItem action) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionPid", action.getPid());
        data.put("actionCode", action.getActionCode());
        data.put("actionType", action.getActionType());
        data.put("targetModel", action.getTargetModel());
        data.put("targetRecordId", action.getTargetRecordId());
        data.put("targetRecordPid", action.getTargetRecordPid());
        data.put("commandCode", action.getCommandCode());
        data.put("commandResult", action.getCommandResult());
        data.put("riskLevel", action.getRiskLevel());
        data.put("estimatedRisk", action.getEstimatedRisk());
        data.put("fidelity", action.getFidelity());
        if (action.getBeforeSnapshot() != null) {
            data.put("beforeSnapshot", parseJsonValueOrRaw(action.getBeforeSnapshot()));
        }
        if (action.getAfterSnapshot() != null) {
            data.put("afterSnapshot", parseJsonValueOrRaw(action.getAfterSnapshot()));
        }
        if (action.getFieldChanges() != null) {
            data.put("fieldChanges", parseJsonValueOrRaw(action.getFieldChanges()));
        }

        boolean readOnly = "read".equalsIgnoreCase(action.getActionType());
        String status = normalizeResultContractStatus(action.getActionStatus(), action.getCommandResult());
        String renderHint = (action.getBeforeSnapshot() != null || action.getAfterSnapshot() != null
                || action.getFieldChanges() != null) ? "card" : "summary";
        String outputType = readOnly ? "structured_result"
                : ("failed".equals(status) ? "text" : "action_proposal");
        String skillCode = firstNonBlank(action.getSkillCode(), action.getCommandCode(), action.getActionCode());

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("outputType", outputType);
        contract.put("renderHint", renderHint);
        contract.put("actionability", readOnly ? "read_only" : "execute");
        contract.put("data", data);
        contract.put("textSummary", buildContractSummary(action, status));
        contract.put("skillCode", skillCode);
        contract.put("durationMs", 0L);
        contract.put("status", status);

        return AgentResultContractItem.builder()
                .contractId(contractIdForAction(action.getPid()))
                .actionPid(action.getPid())
                .source("ab_agent_action")
                .contract(contract)
                .emittedAt(action.getExecutedAt())
                .build();
    }

    private String buildContractSummary(AgentActionItem action, String status) {
        if (action.getIntentSummary() != null && !action.getIntentSummary().isBlank()) {
            return action.getIntentSummary();
        }
        String code = firstNonBlank(action.getCommandCode(), action.getActionCode(), "action");
        return code + " " + status;
    }

    private String normalizeResultContractStatus(String actionStatus, String commandResult) {
        String raw = firstNonBlank(commandResult, actionStatus, "unknown").toLowerCase();
        if (raw.equals("success") || raw.equals("succeeded")) {
            return "success";
        }
        if (raw.equals("failed") || raw.equals("error")) {
            return "failed";
        }
        if (raw.equals("partial_success")) {
            return "partial_success";
        }
        return "unknown";
    }

    JsonNode parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            log.debug("Ignoring malformed replay JSON object: {}", e.getMessage());
            return null;
        }
    }

    private Object parseJsonValueOrRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            return objectMapper.convertValue(objectMapper.readTree(raw), Object.class);
        } catch (Exception e) {
            return raw;
        }
    }

    static String firstText(JsonNode first, String firstField, JsonNode second, String secondField) {
        String v = text(first, firstField);
        return v != null ? v : text(second, secondField);
    }

    static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            String s = value.asText();
            return s == null || s.isBlank() ? null : s.trim();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return null;
    }

    static Long firstLong(JsonNode first, String firstField, JsonNode second, String secondField) {
        Long v = longValue(first, firstField);
        return v != null ? v : longValue(second, secondField);
    }

    static Long longValue(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String contractIdForAction(String actionPid) {
        return actionPid == null ? null : "rc-" + actionPid;
    }

    // =========================================================================
    // RowMappers
    // =========================================================================

    static final RowMapper<AgentRunListItem> RUN_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");

        // duration_ms preferred (set explicitly when run terminates); when it's
        // NULL but completed_at is set, derive from the timestamp delta.
        // Cast to Number (not Long) so the same code works for both BIGINT and
        // INTEGER columns — historic dev DBs have schema drift where
        // duration_ms was created as INTEGER and PG JDBC returns Integer there,
        // which would otherwise CCE on a hard (Long) cast.
        Number storedDurationNum = (Number) rs.getObject("duration_ms");
        long durationMs;
        if (storedDurationNum != null) {
            durationMs = storedDurationNum.longValue();
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
                .childAggregateCostUsd(getBigDecimal(rs, "child_aggregate_cost"))
                .childAggregateTokens(rs.getObject("child_aggregate_tokens") == null
                        ? 0L : ((Number) rs.getObject("child_aggregate_tokens")).longValue())
                .durationMs(durationMs)
                .createdAt(createdAt == null ? null : createdAt.toInstant())
                .completedAt(completedAt == null ? null : completedAt.toInstant())
                .intentSummary(rs.getString("intent_summary"))
                .build();
    };

    static final RowMapper<AgentActionItem> ACTION_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp executedAt = rs.getTimestamp("executed_at");
        Boolean riskDeviation = (Boolean) rs.getObject("risk_deviation");
        String pid = rs.getString("pid");
        return AgentActionItem.builder()
                .pid(pid)
                .resultContractId(contractIdForAction(pid))
                .stepIndex(getInteger(rs, "step_index"))
                .toolCallIndex(getInteger(rs, "tool_call_index"))
                .actionCode(rs.getString("action_code"))
                .actionType(rs.getString("action_type"))
                .intentSummary(rs.getString("intent_summary"))
                .targetModel(rs.getString("target_model"))
                .targetRecordId(rs.getString("target_record_id"))
                .targetRecordPid(rs.getString("target_record_id"))
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

    static Integer getInteger(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static BigDecimal getBigDecimal(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return rs.wasNull() ? null : v;
    }
}
