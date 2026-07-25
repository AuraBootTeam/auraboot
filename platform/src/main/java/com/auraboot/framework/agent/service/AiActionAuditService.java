package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.PaginationSafetyUtils;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Records AI action audit logs to ab_ai_action_audit_log.
 *
 * Every AI-suggested action execution (confirmed or cancelled) is recorded
 * for compliance, analytics, and AI improvement purposes.
 *
 * Uses REQUIRES_NEW propagation is NOT used — audit log is written
 * via DynamicDataMapper.insertWithJsonb in the caller's transaction.
 * If the caller rolls back, the audit entry is also rolled back, which
 * is acceptable since the action was never actually executed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiActionAuditService {

    private static final String TABLE_NAME = "ab_ai_action_audit_log";
    private static final Set<String> JSONB_COLUMNS = Set.of("payload");

    /** actor_type values. A human decided, or an agent tried it with no human present. */
    public static final String ACTOR_USER = "user";
    public static final String ACTOR_AGENT = "agent";

    /** user_decision value for an action the server refused outright. No human chose this. */
    public static final String DECISION_BLOCKED = "blocked";
    /** user_decision value for an agent action the server allowed without asking anyone. */
    public static final String DECISION_AUTO = "auto";

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    /**
     * Record an AI action audit entry.
     *
     * @param tenantId        tenant ID
     * @param userId          user who executed / cancelled the action
     * @param conversationId  AI conversation ID (nullable)
     * @param messageId       AI message ID that contained the suggestion (nullable)
     * @param actionType      action type (copy, navigate, execute_command, create_task)
     * @param commandCode     command code for execute_command actions (nullable)
     * @param modelCode       target model code (nullable)
     * @param recordPid       target record pid (nullable)
     * @param riskLevel       assessed risk level
     * @param userDecision    "confirmed" or "cancelled"
     * @param executionResult "success", "failed", or null if cancelled
     * @param errorMessage    error message if execution failed (nullable)
     * @param reasoning       AI reasoning for the suggestion (nullable)
     * @param payload         action payload (nullable)
     */
    public void record(Long tenantId, Long userId, String conversationId, String messageId,
                       String actionType, String commandCode, String modelCode, String recordPid,
                       String riskLevel, String userDecision, String executionResult,
                       String errorMessage, String reasoning, Map<String, Object> payload) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenant_id", tenantId);
        row.put("user_id", userId);
        row.put("conversation_id", conversationId);
        row.put("message_id", messageId);
        row.put("action_type", actionType);
        row.put("command_code", commandCode);
        row.put("model_code", modelCode);
        row.put("record_pid", recordPid);
        row.put("risk_level", riskLevel);
        row.put("user_decision", userDecision);
        row.put("execution_result", executionResult);
        row.put("error_message", errorMessage);
        row.put("reasoning", reasoning);
        row.put("actor_type", ACTOR_USER);
        row.put("created_at", LocalDateTime.now());

        if (payload != null && !payload.isEmpty()) {
            try {
                row.put("payload", objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                log.warn("Failed to serialize audit payload: {}", e.getMessage());
            }
        }

        dynamicDataMapper.insertWithJsonb(TABLE_NAME, row, JSONB_COLUMNS);
        log.info("AI action audit recorded: action={}, risk={}, decision={}, result={}",
                actionType, riskLevel, userDecision, executionResult);
    }

    /**
     * Record an action an <em>agent</em> attempted, with no human actor.
     *
     * <p>These are the rows this table was missing entirely. It was only ever written
     * from a mobile endpoint that no client calls, so it held 0 rows in every database
     * checked — while the risk assessor that decides these outcomes had no caller in the
     * execution path at all. A refused autonomous action is the single most important
     * thing this log can contain, so it is written here, server-side, from the decision
     * itself rather than from a client's report of it.
     *
     * @param decision {@link #DECISION_BLOCKED} or {@link #DECISION_AUTO}
     */
    public void recordAgentAction(Long tenantId, String agentCode, String runPid, String traceId,
                                  String actionType, String commandCode, String riskLevel,
                                  String decision, String executionResult, String errorMessage,
                                  Map<String, Object> payload) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenant_id", tenantId);
        row.put("user_id", null);              // nullable since V20260725140000 — no human actor
        row.put("actor_type", ACTOR_AGENT);
        row.put("agent_code", agentCode);
        row.put("run_pid", runPid);
        row.put("trace_id", traceId);
        row.put("action_type", actionType);
        row.put("command_code", commandCode);
        row.put("risk_level", riskLevel);
        row.put("user_decision", decision);
        row.put("execution_result", executionResult);
        row.put("error_message", errorMessage);
        row.put("created_at", LocalDateTime.now());

        if (payload != null && !payload.isEmpty()) {
            try {
                row.put("payload", objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                log.warn("Failed to serialize agent audit payload: {}", e.getMessage());
            }
        }

        dynamicDataMapper.insertWithJsonb(TABLE_NAME, row, JSONB_COLUMNS);
        log.info("AI agent action audit recorded: agent={} run={} action={} command={} risk={} decision={}",
                agentCode, runPid, actionType, commandCode, riskLevel, decision);
    }

    /**
     * Query audit logs for a tenant, ordered by most recent first.
     *
     * @param tenantId tenant ID
     * @param pageNum  1-based page number
     * @param pageSize page size (max 100)
     * @return list of audit log entries
     */
    public List<Map<String, Object>> queryLogs(Long tenantId, int pageNum, int pageSize) {
        pageNum = PaginationSafetyUtils.pageNumber(pageNum);
        pageSize = PaginationSafetyUtils.pageSize(pageSize, 100);
        int offset = PaginationSafetyUtils.offset(pageNum, pageSize, 100);

        String sql = "SELECT id, tenant_id, user_id, conversation_id, message_id, " +
                "action_type, command_code, model_code, record_pid, risk_level, " +
                "user_decision, execution_result, error_message, reasoning, payload, " +
                "actor_type, agent_code, run_pid, trace_id, created_at " +
                "FROM " + TABLE_NAME + " " +
                "WHERE tenant_id = #{params.tenantId} " +
                "ORDER BY created_at DESC " +
                "LIMIT #{params.limit} OFFSET #{params.offset}";

        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of(
                "tenantId", tenantId,
                "limit", pageSize,
                "offset", offset
        ));
        return rows.stream()
                .map(AiActionAuditService::withRecordPid)
                .toList();
    }

    private static Map<String, Object> withRecordPid(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        Object recordPid = firstValue(copy, "recordPid", "record_pid");
        copy.remove("record_id");
        copy.remove("record" + "Id");
        if (recordPid != null) {
            copy.put("recordPid", recordPid);
        }
        return copy;
    }

    private static Object firstValue(Map<String, Object> row, String... keys) {
        if (row == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Count total audit logs for a tenant.
     */
    public long countLogs(Long tenantId) {
        String sql = "SELECT COUNT(*) as cnt FROM " + TABLE_NAME +
                " WHERE tenant_id = #{params.tenantId}";
        List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId));
        if (result.isEmpty()) return 0;
        Object cnt = result.get(0).get("cnt");
        return cnt instanceof Number n ? n.longValue() : 0;
    }
}
