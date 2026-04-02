package com.auraboot.framework.agent.service;

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
     * @param recordId        target record ID (nullable)
     * @param riskLevel       assessed risk level
     * @param userDecision    "confirmed" or "cancelled"
     * @param executionResult "success", "failed", or null if cancelled
     * @param errorMessage    error message if execution failed (nullable)
     * @param reasoning       AI reasoning for the suggestion (nullable)
     * @param payload         action payload (nullable)
     */
    public void record(Long tenantId, Long userId, String conversationId, String messageId,
                       String actionType, String commandCode, String modelCode, String recordId,
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
        row.put("record_id", recordId);
        row.put("risk_level", riskLevel);
        row.put("user_decision", userDecision);
        row.put("execution_result", executionResult);
        row.put("error_message", errorMessage);
        row.put("reasoning", reasoning);
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
     * Query audit logs for a tenant, ordered by most recent first.
     *
     * @param tenantId tenant ID
     * @param pageNum  1-based page number
     * @param pageSize page size (max 100)
     * @return list of audit log entries
     */
    public List<Map<String, Object>> queryLogs(Long tenantId, int pageNum, int pageSize) {
        pageSize = Math.min(pageSize, 100);
        int offset = (pageNum - 1) * pageSize;

        String sql = "SELECT id, tenant_id, user_id, conversation_id, message_id, " +
                "action_type, command_code, model_code, record_id, risk_level, " +
                "user_decision, execution_result, error_message, reasoning, payload, created_at " +
                "FROM " + TABLE_NAME + " " +
                "WHERE tenant_id = #{params.tenantId} " +
                "ORDER BY created_at DESC " +
                "LIMIT #{params.limit} OFFSET #{params.offset}";

        return dynamicDataMapper.selectByQuery(sql, Map.of(
                "tenantId", tenantId,
                "limit", pageSize,
                "offset", offset
        ));
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
