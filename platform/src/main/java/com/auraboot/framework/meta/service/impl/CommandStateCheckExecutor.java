package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.entity.StateGraphDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.StateGraphService;
import com.auraboot.framework.meta.service.StateTransitionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Handles STATE_CHECK phase of the command execution pipeline.
 * Validates state transitions and writes new state to the record.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CommandStateCheckExecutor {

    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metaModelService;
    private final StateTransitionEngine stateTransitionEngine;
    private final StateGraphService stateGraphService;
    private final CommandSpelEvaluator spelEvaluator;

    @SuppressWarnings("unchecked")
    String executeStateCheckPhase(CommandDefinition command,
                                   Map<String, Object> payload,
                                   Long tenantId,
                                   CommandExecuteRequest request,
                                   Map<String, Object> execConfig) {
        // Only check state for operations with a target record
        if (request == null || !StringUtils.hasText(request.getTargetRecordId())) {
            return null;
        }

        // Get state field from executionConfig DSL or from state graph definition
        String stateField = null;
        if (execConfig != null && execConfig.containsKey("stateField")) {
            stateField = (String) execConfig.get("stateField");
        }
        if (stateField == null) {
            stateField = getStateFieldForModel(command.getModelCode());
        }
        if (stateField == null) {
            return null;
        }

        // Read current state from the dynamic table
        String currentState = readCurrentState(tenantId, command.getModelCode(),
                request.getTargetRecordId(), stateField);
        if (currentState == null) {
            return null;
        }

        // Check fromStates constraint from DSL config
        if (execConfig != null && execConfig.containsKey("fromStates")) {
            List<String> fromStates = (List<String>) execConfig.get("fromStates");
            if (fromStates != null && !fromStates.isEmpty() && !fromStates.contains(currentState)) {
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "Current state '" + currentState + "' is not in allowed states: " + fromStates);
            }
        }

        // Try multi-branch stateTransitionRules first (DSL config)
        if (execConfig != null && execConfig.containsKey("stateTransitionRules")) {
            List<Map<String, Object>> rules = (List<Map<String, Object>>) execConfig.get("stateTransitionRules");
            String resolved = spelEvaluator.resolveMultiBranchTargetState(rules, payload);
            if (resolved != null) {
                writeStateTransition(tenantId, command.getModelCode(), request.getTargetRecordId(), stateField, resolved);
                return resolved;
            }
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "No matching state transition rule for command: " + command.getCode());
        }

        // Simple toState from DSL config
        if (execConfig != null && execConfig.containsKey("toState")) {
            String toState = (String) execConfig.get("toState");
            writeStateTransition(tenantId, command.getModelCode(), request.getTargetRecordId(), stateField, toState);
            return toState;
        }

        // Fallback: use state graph definitions
        stateTransitionEngine.validateTransition(tenantId, command.getModelCode(),
                stateField, currentState, command.getCode(), payload);

        String targetState = stateTransitionEngine.resolveTargetState(tenantId, command.getModelCode(),
                currentState, command.getCode());
        if (targetState != null) {
            writeStateTransition(tenantId, command.getModelCode(), request.getTargetRecordId(), stateField, targetState);
        }
        return targetState;
    }

    String getStateFieldForModel(String modelCode) {
        if (!StringUtils.hasText(modelCode)) {
            return null;
        }
        try {
            List<StateGraphDefinition> graphs = stateGraphService.listByModelCode(modelCode);
            if (graphs == null || graphs.isEmpty()) {
                return null;
            }
            // Find the first published state graph
            for (StateGraphDefinition graph : graphs) {
                if (StatusConstants.PUBLISHED.equals(graph.getStatus()) && StringUtils.hasText(graph.getStateField())) {
                    return graph.getStateField();
                }
            }
            // Fallback to any graph with state field
            for (StateGraphDefinition graph : graphs) {
                if (StringUtils.hasText(graph.getStateField())) {
                    return graph.getStateField();
                }
            }
            Optional<ModelDefinition> modelOpt = metaModelService.getModelDefinition(modelCode);
            if (modelOpt.isPresent() && modelOpt.get().getFields() != null) {
                List<FieldDefinition> fields = modelOpt.get().getFields();
                for (FieldDefinition field : fields) {
                    String code = field.getCode();
                    if (StringUtils.hasText(code) && code.endsWith("_status")) {
                        return code;
                    }
                }
                for (FieldDefinition field : fields) {
                    String columnName = field.getColumnName();
                    if (StringUtils.hasText(columnName) && columnName.endsWith("_status")) {
                        return columnName;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to get state field for model {}: {}", modelCode, e.getMessage());
            return null;
        }
    }

    String readCurrentState(Long tenantId, String modelCode,
                             String recordId, String stateField) {
        try {
            // Security: validate stateField and tableName to prevent SQL injection
            CommandExecutorUtils.validateSqlIdentifier(stateField, "stateField");
            String tableName = metaModelService.getTableName(modelCode);
            CommandExecutorUtils.validateSqlIdentifier(tableName, "state check tableName");
            var idEntry = CommandExecutorUtils.resolveRecordIdColumn(recordId);
            String sql = "SELECT " + stateField + " FROM " + tableName
                    + " WHERE tenant_id = #{params.tenantId} AND " + idEntry.getKey() + " = #{params.recordId}";
            Map<String, Object> params = Map.of("tenantId", tenantId, "recordId", idEntry.getValue());
            List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql, params);
            if (result == null || result.isEmpty()) {
                return null;
            }
            Object value = result.get(0).get(stateField);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to read current state for model={}, recordId={}, stateField={}: {}",
                    modelCode, recordId, stateField, e.getMessage());
            return null;
        }
    }

    private void writeStateTransition(Long tenantId, String modelCode, String recordId,
                                       String stateField, String newState) {
        try {
            String tableName = metaModelService.getTableName(modelCode);
            var idEntry = CommandExecutorUtils.resolveRecordIdColumn(recordId);
            Map<String, Object> data = Map.of(stateField, newState);
            Map<String, Object> conditions = Map.of("tenant_id", tenantId, idEntry.getKey(), idEntry.getValue());
            dynamicDataMapper.update(tableName, data, conditions);
        } catch (Exception e) {
            log.error("Failed to write state transition for model={}, record={}, state={}: {}",
                    modelCode, recordId, newState, e.getMessage());
            throw new BusinessException(ResponseCode.BadParam, "Failed to update state: " + e.getMessage());
        }
    }
}
