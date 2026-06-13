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
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Handles STATE_CHECK phase of the command execution pipeline.
 * Validates state transitions and writes new state to the record.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandStateCheckExecutor {

    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metaModelService;
    private final StateTransitionEngine stateTransitionEngine;
    private final StateGraphService stateGraphService;
    private final CommandSpelEvaluator spelEvaluator;

    @SuppressWarnings("unchecked")
    public String executeStateCheckPhase(CommandDefinition command,
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

    public String getStateFieldForModel(String modelCode) {
        if (!StringUtils.hasText(modelCode)) {
            return null;
        }
        try {
            List<StateGraphDefinition> graphs = stateGraphService.listByModelCode(modelCode);
            if (graphs != null && !graphs.isEmpty()) {
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
            }
            // Field-based fallback (reached even when NO state graph is registered). The
            // previous early `return null` on empty graphs left models whose state lives in
            // a `*_status` field (e.g. e2et_order_status) without a resolvable state field on
            // the @Async on_state_change bridge, so toState was never read back and a
            // toStates filter never matched (golden FINDING-4b).
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

    public String readCurrentState(Long tenantId, String modelCode,
                             String recordId, String stateField) {
        try {
            // Security: validate stateField and tableName to prevent SQL injection
            CommandExecutorUtils.validateSqlIdentifier(stateField, "stateField");
            String tableName = metaModelService.getTableName(modelCode);
            CommandExecutorUtils.validateSqlIdentifier(tableName, "state check tableName");
            FieldDefinition stateDefinition = findFieldDefinition(modelCode, stateField).orElse(null);
            String selectExpression = stateField;
            if (stateDefinition != null && stateDefinition.isJsonbVirtual()) {
                CommandExecutorUtils.validateSqlIdentifier(stateDefinition.getJsonbColumn(), "state jsonb column");
                CommandExecutorUtils.validateSqlIdentifier(stateDefinition.getJsonbPath(), "state jsonb path");
                selectExpression = stateDefinition.getJsonbColumn()
                        + "->>'" + stateDefinition.getJsonbPath() + "' AS " + stateField;
            }
            var idEntry = CommandExecutorUtils.resolveRecordIdColumn(recordId);
            // The `pid` column is a globally-unique ULID, so a pid lookup identifies exactly
            // one row across all tenants and needs no tenant predicate. This is essential on
            // the @Async on_state_change automation bridge: it runs after-commit on an
            // executor thread where a tenant-scoped read silently returned no row, so the
            // read-back state was null and a toStates filter never matched (golden FINDING-4b).
            // A numeric `id` is a per-tenant sequence, so it still needs the tenant scope.
            // Either way we use selectByQueryWithoutTenant to bypass the TenantLineInnerInterceptor
            // (which would append an empty-tenant predicate off the async thread).
            boolean byPid = "pid".equals(idEntry.getKey());
            String sql = "SELECT " + selectExpression + " FROM " + tableName
                    + " WHERE " + idEntry.getKey() + " = #{params.recordId}"
                    + (byPid ? "" : " AND tenant_id = #{params.tenantId}");
            Map<String, Object> params = byPid
                    ? Map.of("recordId", idEntry.getValue())
                    : Map.of("tenantId", tenantId, "recordId", idEntry.getValue());
            List<Map<String, Object>> result = dynamicDataMapper.selectByQueryWithoutTenant(sql, params);
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
            Optional<ModelDefinition> modelOpt = metaModelService.getModelDefinition(modelCode);
            FieldDefinition stateDefinition = modelOpt
                    .flatMap(model -> findFieldDefinition(model, stateField))
                    .orElse(null);
            var idEntry = CommandExecutorUtils.resolveRecordIdColumn(recordId);
            Map<String, Object> conditions = Map.of("tenant_id", tenantId, idEntry.getKey(), idEntry.getValue());
            int updated;
            if (modelOpt.isPresent() && stateDefinition != null && stateDefinition.isJsonbVirtual()) {
                CommandExecutorUtils.validateSqlIdentifier(stateDefinition.getJsonbColumn(), "state jsonb column");
                CommandExecutorUtils.validateSqlIdentifier(stateDefinition.getJsonbPath(), "state jsonb path");
                Map<String, Object> existingVirtualValues = readJsonbVirtualValues(
                        tenantId, tableName, recordId, modelOpt.get(), stateDefinition.getJsonbColumn());
                Map<String, Object> merged = JsonbFieldHelper.mergeJsonbFieldsForUpdate(
                        modelOpt.get(), Map.of(stateField, newState), existingVirtualValues);
                Map<String, Object> data = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : merged.entrySet()) {
                    Object value = entry.getValue();
                    data.put(entry.getKey(), JsonbFieldHelper.shouldSerializeJsonValue(value)
                            ? JsonbFieldHelper.toJsonString(value)
                            : value);
                }
                data.put("updated_at", Instant.now());
                updated = dynamicDataMapper.updateWithJsonb(
                        tableName, data, conditions, Set.of(stateDefinition.getJsonbColumn()));
            } else {
                Map<String, Object> data = Map.of(stateField, newState);
                updated = dynamicDataMapper.update(tableName, data, conditions);
            }
            if (updated == 0) {
                throw new BusinessException(ResponseCode.BadParam,
                        "state transition affected 0 rows for " + modelCode + " id=" + recordId);
            }
        } catch (Exception e) {
            log.error("Failed to write state transition for model={}, record={}, state={}: {}",
                    modelCode, recordId, newState, e.getMessage());
            throw new BusinessException(ResponseCode.BadParam, "Failed to update state: " + e.getMessage());
        }
    }

    private Optional<FieldDefinition> findFieldDefinition(String modelCode, String fieldCode) {
        return metaModelService.getModelDefinition(modelCode)
                .flatMap(model -> findFieldDefinition(model, fieldCode));
    }

    private Optional<FieldDefinition> findFieldDefinition(ModelDefinition model, String fieldCode) {
        if (model == null || model.getFields() == null || !StringUtils.hasText(fieldCode)) {
            return Optional.empty();
        }
        return model.getFields().stream()
                .filter(field -> fieldCode.equals(field.getCode()) || fieldCode.equals(field.getColumnName()))
                .findFirst();
    }

    private Map<String, Object> readJsonbVirtualValues(Long tenantId, String tableName, String recordId,
                                                       ModelDefinition model, String jsonbColumn) {
        try {
            CommandExecutorUtils.validateSqlIdentifier(tableName, "state jsonb tableName");
            CommandExecutorUtils.validateSqlIdentifier(jsonbColumn, "state jsonb column");
            List<String> expressions = new ArrayList<>();
            for (FieldDefinition field : model.getFields()) {
                if (!field.isJsonbVirtual() || !jsonbColumn.equals(field.getJsonbColumn())) {
                    continue;
                }
                CommandExecutorUtils.validateSqlIdentifier(field.getCode(), "state jsonb virtual field");
                CommandExecutorUtils.validateSqlIdentifier(field.getJsonbPath(), "state jsonb path");
                expressions.add(jsonbColumn + "->>'" + field.getJsonbPath() + "' AS " + field.getCode());
            }
            if (expressions.isEmpty()) {
                return Map.of();
            }
            var idEntry = CommandExecutorUtils.resolveRecordIdColumn(recordId);
            String sql = "SELECT " + String.join(", ", expressions)
                    + " FROM " + tableName
                    + " WHERE " + idEntry.getKey() + " = #{params.recordId}"
                    + " AND tenant_id = #{params.tenantId}";
            Map<String, Object> params = Map.of("tenantId", tenantId, "recordId", idEntry.getValue());
            List<Map<String, Object>> result = dynamicDataMapper.selectByQueryWithoutTenant(sql, params);
            if (result == null || result.isEmpty()) {
                return Map.of();
            }
            return result.get(0);
        } catch (Exception e) {
            log.warn("Failed to read JSONB virtual state values for model={}, recordId={}, column={}: {}",
                    model.getCode(), recordId, jsonbColumn, e.getMessage());
            return Map.of();
        }
    }
}
