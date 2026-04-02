package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AiActionRiskLevel;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assesses risk level for AI-suggested actions.
 *
 * Risk is derived from the action type and, for execute_command actions,
 * from the command's execution_config.type in ab_command_definition.
 *
 * The risk classification follows the design doc (18-ai-action-safety.md):
 * - L1 (LOW): copy, navigate — read-only / no side effects
 * - L2 (MEDIUM): create_task — creates new data but reversible
 * - L3 (HIGH): execute_command with state_transition or delete
 * - BLOCKED: delete commands, permission changes — AI must not suggest
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiActionRiskAssessor {

    private final DynamicDataMapper dynamicDataMapper;

    /** Action types that are always LOW risk (no data modification). */
    private static final Set<String> LOW_RISK_ACTIONS = Set.of("copy", "navigate");

    /** Action types that are MEDIUM risk (creates data, reversible). */
    private static final Set<String> MEDIUM_RISK_ACTIONS = Set.of("create_task");

    /** Command execution types that escalate to HIGH risk. */
    private static final Set<String> HIGH_RISK_EXECUTION_TYPES = Set.of(
            "state_transition", "delete", "bulk_delete", "bulk_update"
    );

    /** Command execution types that are BLOCKED (AI must never suggest). */
    private static final Set<String> BLOCKED_EXECUTION_TYPES = Set.of("delete");

    /**
     * Assess risk level for an AI action.
     *
     * @param actionType  the action type (copy, navigate, execute_command, create_task)
     * @param commandCode optional command code for execute_command actions
     * @param tenantId    tenant ID for looking up command definitions
     * @return the assessed risk level
     */
    public AiActionRiskLevel assess(String actionType, String commandCode, Long tenantId) {
        if (actionType == null) {
            return AiActionRiskLevel.LOW;
        }

        // Low-risk actions: no confirmation needed
        if (LOW_RISK_ACTIONS.contains(actionType)) {
            return AiActionRiskLevel.LOW;
        }

        // Medium-risk actions: standard confirmation
        if (MEDIUM_RISK_ACTIONS.contains(actionType)) {
            return AiActionRiskLevel.MEDIUM;
        }

        // execute_command: risk depends on the command definition
        if ("execute_command".equals(actionType) && commandCode != null && tenantId != null) {
            return assessCommandRisk(commandCode, tenantId);
        }

        // Default: MEDIUM for unknown action types (safer than LOW)
        return AiActionRiskLevel.MEDIUM;
    }

    /**
     * Look up the command's execution type from ab_command_definition
     * and derive the risk level.
     */
    private AiActionRiskLevel assessCommandRisk(String commandCode, Long tenantId) {
        String executionType = resolveExecutionType(commandCode, tenantId);

        if (BLOCKED_EXECUTION_TYPES.contains(executionType)) {
            return AiActionRiskLevel.BLOCKED;
        }

        if (HIGH_RISK_EXECUTION_TYPES.contains(executionType)) {
            return AiActionRiskLevel.HIGH;
        }

        // create, update commands via execute_command are MEDIUM
        return AiActionRiskLevel.MEDIUM;
    }

    /**
     * Resolve execution type from ab_command_definition.
     */
    private String resolveExecutionType(String commandCode, Long tenantId) {
        try {
            String sql = "SELECT execution_config FROM ab_command_definition " +
                    "WHERE tenant_id = #{params.tenantId} AND code = #{params.code} " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "code", commandCode));

            if (!rows.isEmpty()) {
                Object execConfig = rows.get(0).get("execution_config");
                if (execConfig instanceof Map<?, ?> configMap) {
                    Object type = configMap.get("type");
                    if (type != null) {
                        return type.toString().toLowerCase();
                    }
                } else if (execConfig instanceof String configStr) {
                    // Parse JSON string
                    if (configStr.contains("\"type\"")) {
                        int idx = configStr.indexOf("\"type\"");
                        int valueStart = configStr.indexOf("\"", idx + 7);
                        int valueEnd = configStr.indexOf("\"", valueStart + 1);
                        if (valueStart >= 0 && valueEnd > valueStart) {
                            return configStr.substring(valueStart + 1, valueEnd).toLowerCase();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve execution type for command {}: {}", commandCode, e.getMessage());
        }

        // Default to update if we can't determine
        return "update";
    }
}
