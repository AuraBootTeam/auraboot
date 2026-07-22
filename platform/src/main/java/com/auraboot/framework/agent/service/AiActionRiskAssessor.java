package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AiActionRiskLevel;
import com.auraboot.framework.agent.util.JsonbColumns;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

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
        CommandRiskInputs inputs = resolveCommandRiskInputs(commandCode, tenantId);
        String executionType = inputs.executionType();

        if (BLOCKED_EXECUTION_TYPES.contains(executionType)) {
            // "Never offer this" is not a score, and nothing declared in
            // cmd_risk_level can lift it — L4 means irreversible, which is a
            // different statement from forbidden.
            return AiActionRiskLevel.BLOCKED;
        }

        AiActionRiskLevel fromExecutionType = HIGH_RISK_EXECUTION_TYPES.contains(executionType)
                ? AiActionRiskLevel.HIGH
                // create, update commands via execute_command are MEDIUM
                : AiActionRiskLevel.MEDIUM;

        // The platform's own L0-L4, declared on the command and sitting in the
        // row this class was already reading. Until now it went unread and this
        // scale was derived independently, so the confirmation a person saw
        // could disagree with the approval the platform demanded and nothing
        // anywhere would fail.
        //
        // Strictest wins. Reading the declared level on its own would let the
        // l1 column default downgrade an unlabelled delete from HIGH to
        // MEDIUM — the same silent downgrade this class was already fixed for
        // once. Connecting the two scales may tighten a confirmation; it must
        // never loosen one.
        if (inputs.declaredLevel() == null || inputs.declaredLevel().isBlank()) {
            return fromExecutionType;
        }
        AiActionRiskLevel declared = AiActionRiskLevel.fromPlatformRiskLevel(inputs.declaredLevel());
        return declared.ordinal() > fromExecutionType.ordinal() ? declared : fromExecutionType;
    }

    /** What the command row says about its own risk, from a single read. */
    private record CommandRiskInputs(String executionType, String declaredLevel) {
    }

    /**
     * Resolve execution type from ab_command_definition.
     */
    @SuppressWarnings("unchecked")
    private CommandRiskInputs resolveCommandRiskInputs(String commandCode, Long tenantId) {
        String declaredLevel = null;
        try {
            String sql = "SELECT execution_config, cmd_risk_level FROM ab_command_definition " +
                    "WHERE tenant_id = #{params.tenantId} AND code = #{params.code} " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "code", commandCode));

            if (!rows.isEmpty()) {
                Object declared = rows.get(0).get("cmd_risk_level");
                declaredLevel = declared == null ? null : declared.toString();
                Object execConfig = rows.get(0).get("execution_config");
                Map<String, Object> configMap;
                if (execConfig instanceof Map<?, ?> m) {
                    configMap = (Map<String, Object>) m;
                } else {
                    // execution_config is JSONB; read via the generic selectByQuery it
                    // comes back as a driver PGobject (neither Map nor String). The old
                    // instanceof checks fell through and defaulted to "update", which
                    // silently DOWNGRADED the risk gating for delete / state_transition
                    // commands (a BLOCKED delete would assess as MEDIUM). Route through
                    // JsonbColumns so the type is read on every driver shape.
                    String json = JsonbColumns.toJsonText(execConfig, objectMapper);
                    configMap = json == null
                            ? null
                            : objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                }
                if (configMap != null) {
                    Object type = configMap.get("type");
                    if (type != null) {
                        return new CommandRiskInputs(type.toString().toLowerCase(), declaredLevel);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve execution type for command {}: {}", commandCode, e.getMessage());
        }

        // Default to update if we can't determine. The declared level still
        // travels: failing to read execution_config is not a reason to discard
        // what the command said about itself.
        return new CommandRiskInputs("update", declaredLevel);
    }
}
