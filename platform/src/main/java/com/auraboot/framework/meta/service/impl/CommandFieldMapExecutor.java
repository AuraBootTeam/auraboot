package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.auraboot.framework.common.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles FIELD_MAP phase of the command execution pipeline.
 * Supports both explicit binding rules and implicit inputFields-based field mapping.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandFieldMapExecutor {

    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metaModelService;

    public Map<String, Object> executeFieldMapPhase(List<BindingRule> fieldMapRules,
                                              Map<String, Object> payload,
                                              Long tenantId,
                                              CommandExecuteRequest request) {
        Map<String, Object> results = new HashMap<>();

        // Group rules by target model
        Map<String, List<BindingRule>> rulesByModel = fieldMapRules.stream()
                .filter(r -> StringUtils.hasText(r.getTargetModel()))
                .collect(Collectors.groupingBy(BindingRule::getTargetModel));

        for (Map.Entry<String, List<BindingRule>> entry : rulesByModel.entrySet()) {
            String targetModel = entry.getKey();
            List<BindingRule> rules = entry.getValue();

            // Build data map from payload field mappings
            Map<String, Object> data = new HashMap<>();
            data.put("tenant_id", tenantId);

            // Load model definition to check virtual field types and get table name
            ModelDefinition modelDef = null;
            String tableName = targetModel; // fallback to model code
            try {
                modelDef = metaModelService.getModelDefinition(targetModel).orElse(null);
                if (modelDef != null && modelDef.getTableName() != null) {
                    tableName = modelDef.getTableName();
                }
            } catch (Exception e) {
                log.debug("Could not load model definition for {}, using model code as table name", targetModel);
            }

            for (BindingRule rule : rules) {
                String sourceField = rule.getSourceField();
                String targetField = rule.getTargetField();
                if (StringUtils.hasText(sourceField) && StringUtils.hasText(targetField)) {
                    // Skip computed readonly and transient virtual fields
                    if (modelDef != null && isVirtualReadonlyField(modelDef, targetField)) {
                        continue;
                    }
                    Object value = payload.get(sourceField);
                    if (value != null) {
                        data.put(targetField, value);
                    }
                }
            }

            convertModelFieldTypes(modelDef, data);

            // Merge JSONB virtual fields and map field codes to column names
            Set<String> jsonbCols = modelDef != null ? JsonbFieldHelper.getJsonbHostColumns(modelDef) : Set.of();

            // For UPDATE: load existing JSONB data to preserve unmodified keys
            String operationType = request.getOperationType();
            if ("update".equalsIgnoreCase(operationType) && StringUtils.hasText(request.getTargetRecordId())
                    && !jsonbCols.isEmpty()) {
                injectExistingJsonbData(tableName, request.getTargetRecordId(), tenantId, jsonbCols, data);
            }

            Map<String, Object> columnData = prepareColumnData(modelDef, data);

            // Determine operation type - use actual table name for database operations
            if ("update".equalsIgnoreCase(operationType) && StringUtils.hasText(request.getTargetRecordId())) {
                // F-5 fix: UPDATE must refresh updated_at — DDL DEFAULT CURRENT_TIMESTAMP only
                // applies to INSERT, so without explicit assignment audit timestamps stale forever.
                columnData.put("updated_at", Instant.now());
                Map<String, Object> conditions = new HashMap<>();
                conditions.put("tenant_id", tenantId);
                var idEntry = CommandExecutorUtils.resolveRecordIdColumn(request.getTargetRecordId());
                conditions.put(idEntry.getKey(), idEntry.getValue());
                int updated = jsonbCols.isEmpty()
                        ? dynamicDataMapper.update(tableName, columnData, conditions)
                        : dynamicDataMapper.updateWithJsonb(tableName, columnData, conditions, jsonbCols);
                if (updated == 0) {
                    // F-5 defense: silent code=0 with affected=0 hides "record not found / wrong tenant"
                    // bugs from callers. Reject explicitly so the caller sees BadParam, not a fake 200.
                    throw new BusinessException(ResponseCode.BadParam,
                            "update affected 0 rows for " + targetModel + " id=" + request.getTargetRecordId()
                                    + " (record not found or tenant mismatch)");
                }
                results.put(targetModel + "_updated", updated);
            } else if ("delete".equalsIgnoreCase(operationType) && StringUtils.hasText(request.getTargetRecordId())) {
                Map<String, Object> conditions = new HashMap<>();
                conditions.put("tenant_id", tenantId);
                var delIdEntry = CommandExecutorUtils.resolveRecordIdColumn(request.getTargetRecordId());
                conditions.put(delIdEntry.getKey(), delIdEntry.getValue());
                int deleted = dynamicDataMapper.delete(tableName, conditions);
                results.put(targetModel + "_deleted", deleted);
            } else {
                // Defense: reject delete/update without targetRecordId.
                // Without this guard the code falls through to INSERT and silently
                // creates a blank row (only DB NOT NULL constraints catch it, by accident).
                if ("delete".equalsIgnoreCase(operationType)) {
                    throw new BusinessException(ResponseCode.BadParam,
                            "targetRecordId is required for delete operations");
                }
                if ("update".equalsIgnoreCase(operationType)) {
                    throw new BusinessException(ResponseCode.BadParam,
                            "targetRecordId is required for update operations");
                }
                // Default: INSERT - generate pid and set audit timestamps for new records
                Instant now = Instant.now();
                columnData.put("pid", UniqueIdGenerator.generate());
                columnData.putIfAbsent("created_at", now);
                columnData.putIfAbsent("updated_at", now);
                int inserted = jsonbCols.isEmpty()
                        ? dynamicDataMapper.insert(tableName, columnData)
                        : dynamicDataMapper.insertWithJsonb(tableName, columnData, jsonbCols);
                results.put(targetModel + "_inserted", inserted);
            }
        }

        return results;
    }

    /**
     * Implicit field mapping: when no FIELD_MAP binding rules exist, use inputFields from execution_config
     * to build the data map and persist to the command's target model.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeImplicitFieldMapPhase(Map<String, Object> execConfig,
                                                      Map<String, Object> payload,
                                                      Long tenantId,
                                                      CommandExecuteRequest request,
                                                      CommandDefinition command) {
        Map<String, Object> results = new HashMap<>();
        String modelCode = command.getModelCode();
        if (!StringUtils.hasText(modelCode)) {
            log.warn("No modelCode on command {}, skipping implicit field map", command.getCode());
            return results;
        }

        String operationType = resolveOperationType(execConfig, request);

        List<String> inputFields = (List<String>) execConfig.get("inputFields");
        Map<String, Object> autoSetFieldsCheck = (Map<String, Object>) execConfig.get("autoSetFields");
        boolean hasAutoSetFields = autoSetFieldsCheck != null && !autoSetFieldsCheck.isEmpty();
        String execType = (String) execConfig.get("type");
        boolean isStateTransition = "state_transition".equalsIgnoreCase(execType);
        boolean isCreateOrUpdate = "create".equalsIgnoreCase(execType) || "update".equalsIgnoreCase(execType);
        if ((inputFields == null || inputFields.isEmpty())
                && !hasAutoSetFields
                && !isStateTransition
                && !isCreateOrUpdate
                && !"delete".equalsIgnoreCase(operationType)) {
            log.debug("No inputFields or autoSetFields in execution_config for command {}", command.getCode());
            return results;
        }
        // When type is CREATE/UPDATE but no explicit inputFields, use all payload keys (implicit mapping)
        if ((inputFields == null || inputFields.isEmpty()) && isCreateOrUpdate && payload != null) {
            inputFields = new java.util.ArrayList<>(payload.keySet());
            log.debug("Implicit inputFields from payload for {}: {}", command.getCode(), inputFields);
        }

        // Resolve table name from model definition
        ModelDefinition modelDef = null;
        String tableName = modelCode;
        try {
            modelDef = metaModelService.getModelDefinition(modelCode).orElse(null);
            if (modelDef != null && modelDef.getTableName() != null) {
                tableName = modelDef.getTableName();
            }
        } catch (Exception e) {
            log.debug("Could not load model definition for {}, using model code as table name", modelCode);
        }

        // Build data map from inputFields (1:1 mapping: payload field -> db column)
        Map<String, Object> data = new HashMap<>();
        data.put("tenant_id", tenantId);
        for (String fieldCode : (inputFields != null ? inputFields : List.<String>of())) {
            Object value = payload.get(fieldCode);
            if (value != null) {
                // Skip virtual/readonly fields
                if (modelDef != null && isVirtualReadonlyField(modelDef, fieldCode)) {
                    continue;
                }
                data.put(fieldCode, value);
            }
        }

        // Also include autoSetFields that were injected into payload during AUTO_SET phase
        Map<String, Object> autoSetFields = (Map<String, Object>) execConfig.get("autoSetFields");
        if (autoSetFields != null) {
            for (String fieldCode : autoSetFields.keySet()) {
                Object value = payload.get(fieldCode);
                if (value != null) {
                    data.put(fieldCode, value);
                }
            }
        }

        // Convert data types based on model field definitions (DATE, INTEGER, DECIMAL, etc.)
        convertModelFieldTypes(modelDef, data);

        // For STATE_TRANSITION commands, inject the target state into the data map
        if (isStateTransition) {
            String stateField = (String) execConfig.get("stateField");
            String toState = (String) execConfig.get("toState");
            if (StringUtils.hasText(stateField) && StringUtils.hasText(toState)) {
                data.put(stateField, toState);
                log.info("STATE_TRANSITION: setting {} = {} (command={})", stateField, toState, command.getCode());
            }
        }

        // Merge JSONB virtual fields and map field codes to column names
        Set<String> jsonbColumns = modelDef != null ? JsonbFieldHelper.getJsonbHostColumns(modelDef) : Set.of();

        // For UPDATE: load existing JSONB data to preserve unmodified keys
        boolean isUpdateLike = "update".equalsIgnoreCase(operationType) || "state_transition".equalsIgnoreCase(operationType);
        if (isUpdateLike && StringUtils.hasText(request.getTargetRecordId()) && !jsonbColumns.isEmpty()) {
            injectExistingJsonbData(tableName, request.getTargetRecordId(), tenantId, jsonbColumns, data);
        }

        Map<String, Object> columnData = prepareColumnData(modelDef, data);

        // Execute the database operation
        if (isUpdateLike && StringUtils.hasText(request.getTargetRecordId())) {
            // F-5 fix: UPDATE must refresh updated_at — DDL DEFAULT CURRENT_TIMESTAMP only
            // applies to INSERT, so without explicit assignment audit timestamps stale forever.
            columnData.put("updated_at", Instant.now());
            Map<String, Object> conditions = new HashMap<>();
            conditions.put("tenant_id", tenantId);
            var idEntry = CommandExecutorUtils.resolveRecordIdColumn(request.getTargetRecordId());
            conditions.put(idEntry.getKey(), idEntry.getValue());
            int updated = jsonbColumns.isEmpty()
                    ? dynamicDataMapper.update(tableName, columnData, conditions)
                    : dynamicDataMapper.updateWithJsonb(tableName, columnData, conditions, jsonbColumns);
            if (updated == 0) {
                // F-5 defense: silent code=0 with affected=0 hides "record not found / wrong tenant"
                // bugs from callers. Reject explicitly so the caller sees BadParam, not a fake 200.
                throw new BusinessException(ResponseCode.BadParam,
                        "update affected 0 rows for " + modelCode + " id=" + request.getTargetRecordId()
                                + " (record not found or tenant mismatch)");
            }
            results.put(modelCode + "_updated", updated);
            log.info("Implicit FIELD_MAP UPDATE: {} rows in {} (command={})", updated, modelCode, command.getCode());
        } else if ("delete".equalsIgnoreCase(operationType) && StringUtils.hasText(request.getTargetRecordId())) {
            Map<String, Object> conditions = new HashMap<>();
            conditions.put("tenant_id", tenantId);
            var delIdEntry = CommandExecutorUtils.resolveRecordIdColumn(request.getTargetRecordId());
            conditions.put(delIdEntry.getKey(), delIdEntry.getValue());
            int deleted = dynamicDataMapper.delete(tableName, conditions);
            results.put(modelCode + "_deleted", deleted);
            log.info("Implicit FIELD_MAP DELETE: {} rows in {} (command={})", deleted, modelCode, command.getCode());
        } else {
            // Defense: reject delete/update without targetRecordId.
            // Without this guard the code falls through to INSERT and silently
            // creates a blank row (only DB NOT NULL constraints catch it, by accident).
            if ("delete".equalsIgnoreCase(operationType)) {
                throw new BusinessException(ResponseCode.BadParam,
                        "targetRecordId is required for delete operations");
            }
            if ("update".equalsIgnoreCase(operationType) || "state_transition".equalsIgnoreCase(operationType)) {
                throw new BusinessException(ResponseCode.BadParam,
                        "targetRecordId is required for update operations");
            }
            // INSERT: generate pid and set audit timestamps for new records
            String newPid = UniqueIdGenerator.generate();
            java.time.Instant now = java.time.Instant.now();
            columnData.put("pid", newPid);
            columnData.putIfAbsent("created_at", now);
            columnData.putIfAbsent("updated_at", now);
            int inserted = jsonbColumns.isEmpty()
                    ? dynamicDataMapper.insert(tableName, columnData)
                    : dynamicDataMapper.insertWithJsonb(tableName, columnData, jsonbColumns);
            results.put(modelCode + "_inserted", inserted);
            results.put("recordId", newPid);
            log.info("Implicit FIELD_MAP INSERT: {} rows in {} (pid={}, command={})", inserted, modelCode, newPid, command.getCode());
        }

        return results;
    }

    private String resolveOperationType(Map<String, Object> execConfig, CommandExecuteRequest request) {
        String operationType = request != null ? request.getOperationType() : null;
        if (StringUtils.hasText(operationType)) {
            return operationType;
        }
        Object type = execConfig != null ? execConfig.get("type") : null;
        return type instanceof String ? (String) type : null;
    }

    /**
     * Convert a field value to the appropriate Java type based on the model field's data type.
     */
    public Object convertFieldValue(String dataType, Object value) {
        if (value == null || dataType == null) return value;
        String dt = dataType.toUpperCase();
        return switch (dt) {
            case "DATE" -> {
                if (value instanceof String s) {
                    yield java.sql.Date.valueOf(parseLocalDate(s));
                }
                yield value;
            }
            case "DATETIME", "TIMESTAMP" -> {
                if (value instanceof String s) {
                    yield Timestamp.valueOf(parseLocalDateTime(s));
                }
                yield value;
            }
            case "INTEGER", "INT" -> {
                if (value instanceof String s) {
                    yield Integer.valueOf(s);
                } else if (value instanceof Number n) {
                    yield n.intValue();
                }
                yield value;
            }
            case "LONG", "BIGINT" -> {
                if (value instanceof String s) {
                    yield Long.valueOf(s);
                } else if (value instanceof Number n) {
                    yield n.longValue();
                }
                yield value;
            }
            case "DECIMAL", "NUMERIC" -> {
                if (value instanceof String s) {
                    yield new java.math.BigDecimal(s);
                } else if (value instanceof Number n) {
                    yield new java.math.BigDecimal(n.toString());
                }
                yield value;
            }
            case "BOOLEAN" -> {
                if (value instanceof String s) {
                    yield Boolean.valueOf(s);
                }
                yield value;
            }
            case "STRING", "TEXT", "VARCHAR", "CHAR" -> {
                if (value instanceof String) {
                    yield value;
                }
                yield String.valueOf(value);
            }
            default -> value;
        };
    }

    private void convertModelFieldTypes(ModelDefinition modelDef, Map<String, Object> data) {
        if (modelDef == null || modelDef.getFields() == null || data.isEmpty()) {
            return;
        }
        for (FieldDefinition field : modelDef.getFields()) {
            String fieldCode = field.getCode();
            Object value = data.get(fieldCode);
            if (value == null || field.getDataType() == null) {
                continue;
            }
            try {
                data.put(fieldCode, convertFieldValue(field.getDataType(), value));
            } catch (Exception e) {
                log.warn("Type conversion failed for field {}: {}", fieldCode, e.getMessage());
            }
        }
    }

    private LocalDate parseLocalDate(String raw) {
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("date value is blank");
        }
        if (normalized.contains("T")) {
            return parseLocalDateTime(normalized).toLocalDate();
        }
        return LocalDate.parse(normalized);
    }

    private LocalDateTime parseLocalDateTime(String raw) {
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("datetime value is blank");
        }
        if (normalized.endsWith("Z") || normalized.matches(".*[+-]\\d{2}:\\d{2}$")) {
            return OffsetDateTime.parse(normalized).toLocalDateTime();
        }
        if (normalized.matches(".*[+-]\\d{4}$")) {
            String withColon = normalized.substring(0, normalized.length() - 5)
                    + normalized.substring(normalized.length() - 5, normalized.length() - 2)
                    + ":"
                    + normalized.substring(normalized.length() - 2);
            return OffsetDateTime.parse(withColon).toLocalDateTime();
        }
        if (normalized.contains("T")) {
            return LocalDateTime.parse(normalized);
        }
        if (normalized.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?")) {
            return Timestamp.valueOf(normalized).toLocalDateTime();
        }
        return Instant.parse(normalized).atOffset(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * Prepare column-level data from field-level data:
     * 1. Merge JSONB virtual fields into host JSONB columns
     * 2. Map field codes to physical column names
     * 3. Serialize JSONB values to JSON strings
     */
    private Map<String, Object> prepareColumnData(ModelDefinition modelDef, Map<String, Object> data) {
        if (modelDef == null || modelDef.getFields() == null) {
            return data; // No model definition — pass through as-is
        }

        // Step 1: Merge JSONB virtual fields into host columns
        Map<String, Object> merged = JsonbFieldHelper.mergeJsonbFields(modelDef, data);

        // Step 2: Map field codes to column names, serialize JSONB
        Map<String, Object> columnData = new LinkedHashMap<>();
        Map<String, String> codeToColumn = new HashMap<>();
        Set<String> hostColumns = JsonbFieldHelper.getJsonbHostColumns(modelDef);
        for (FieldDefinition field : modelDef.getFields()) {
            if (!field.isJsonbVirtual()) {
                codeToColumn.put(field.getCode(), field.getColumnName());
                codeToColumn.put(field.getColumnName(), field.getColumnName());
            }
        }

        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            // System columns pass through directly
            if ("tenant_id".equals(key) || "pid".equals(key) || "created_at".equals(key)
                    || "created_by".equals(key) || "updated_at".equals(key) || "updated_by".equals(key)) {
                columnData.put(key, value);
                continue;
            }
            String columnName = codeToColumn.get(key);
            if (columnName != null) {
                if (hostColumns.contains(columnName) && value instanceof Map) {
                    columnData.put(columnName, JsonbFieldHelper.toJsonString(value));
                } else {
                    columnData.put(columnName, value);
                }
            } else if (hostColumns.contains(key)) {
                columnData.put(key, value instanceof Map ? JsonbFieldHelper.toJsonString(value) : value);
            } else {
                // Unknown field — pass through (might be a system field not in the list)
                columnData.put(key, value);
            }
        }
        return columnData;
    }

    /**
     * For UPDATE: load existing JSONB host column values from the database and inject
     * them into the data map so that mergeJsonbFields() can merge new values into existing ones.
     */
    @SuppressWarnings("unchecked")
    private void injectExistingJsonbData(String tableName, String recordId, Long tenantId,
                                          Set<String> jsonbHostColumns, Map<String, Object> data) {
        if (jsonbHostColumns.isEmpty()) return;
        try {
            var idEntry = CommandExecutorUtils.resolveRecordIdColumn(recordId);
            String whereClause = idEntry.getKey() + " = '" + idEntry.getValue() + "'";
            List<Map<String, Object>> rows = dynamicDataMapper.queryList(
                    tableName, new ArrayList<>(jsonbHostColumns), whereClause, null, 1, 0);
            if (rows != null && !rows.isEmpty()) {
                Map<String, Object> existingRow = rows.get(0);
                for (String hostCol : jsonbHostColumns) {
                    if (data.containsKey(hostCol)) continue; // user explicitly set host column
                    Object rawValue = existingRow.get(hostCol);
                    if (rawValue instanceof String jsonStr && !jsonStr.isBlank()) {
                        try {
                            Map<String, Object> parsed = JsonUtil.parse(jsonStr,
                                    new TypeReference<Map<String, Object>>() {});
                            data.put(hostCol, parsed);
                        } catch (Exception e) {
                            log.debug("Failed to parse existing JSONB column {}: {}", hostCol, e.getMessage());
                        }
                    } else if (rawValue instanceof Map) {
                        data.put(hostCol, rawValue);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load existing JSONB data for UPDATE: {}", e.getMessage());
        }
    }

    boolean isVirtualReadonlyField(ModelDefinition modelDef, String fieldCode) {
        if (modelDef.getFields() == null) {
            return false;
        }
        for (FieldDefinition field : modelDef.getFields()) {
            if (fieldCode.equals(field.getCode()) || fieldCode.equals(field.getColumnName())) {
                return field.isComputedReadonly() || field.isTransientField();
            }
        }
        return false;
    }
}
