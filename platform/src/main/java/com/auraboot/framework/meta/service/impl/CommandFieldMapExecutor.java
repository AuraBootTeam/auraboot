package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
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
    private final FieldPermissionService fieldPermissionService;
    private final DataPermissionEngine dataPermissionEngine;
    private final DataDomainService dataDomainService;

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
            Set<String> userMappedFields = new LinkedHashSet<>();

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
                        userMappedFields.add(targetField);
                    }
                }
            }

            enforceEditableFields(targetModel, modelDef, userMappedFields);
            applyReferencePidCompanions(modelDef, data);
            convertModelFieldTypes(modelDef, data);

            // Merge JSONB virtual fields and map field codes to column names
            Set<String> jsonbCols = resolveJsonbColumns(modelDef, tableName);

            // For UPDATE: load existing JSONB data to preserve unmodified keys
            String operationType = request.getOperationType();
            if ("update".equalsIgnoreCase(operationType) && StringUtils.hasText(request.getTargetRecordId())
                    && !jsonbCols.isEmpty()) {
                injectExistingJsonbData(tableName, request.getTargetRecordId(), tenantId, jsonbCols, data);
            }

            Map<String, Object> columnData = prepareColumnData(modelDef, data, jsonbCols);

            // Determine operation type - use actual table name for database operations
            if ("update".equalsIgnoreCase(operationType) && StringUtils.hasText(request.getTargetRecordId())) {
                // F-5 fix: UPDATE must refresh updated_at — DDL DEFAULT CURRENT_TIMESTAMP only
                // applies to INSERT, so without explicit assignment audit timestamps stale forever.
                columnData.put("updated_at", Instant.now());
                var idEntry = CommandExecutorUtils.resolveRecordIdColumn(request.getTargetRecordId());
                int updated = executeScopedUpdate(tableName, targetModel, idEntry, columnData, tenantId, jsonbCols);
                if (updated == 0) {
                    // F-5 defense: silent code=0 with affected=0 hides "record not found / wrong tenant"
                    // bugs from callers. Reject explicitly so the caller sees BadParam, not a fake 200.
                    throw new BusinessException(ResponseCode.BadParam,
                            "update affected 0 rows for " + targetModel + " id=" + request.getTargetRecordId()
                                    + " (record not found or tenant mismatch)");
                }
                results.put(targetModel + "_updated", updated);
            } else if ("delete".equalsIgnoreCase(operationType) && StringUtils.hasText(request.getTargetRecordId())) {
                var delIdEntry = CommandExecutorUtils.resolveRecordIdColumn(request.getTargetRecordId());
                int deleted;
                if (modelDef != null && modelDef.isSoftDelete()) {
                    // Soft-delete model: flag the row (recoverable/auditable) instead of
                    // physically deleting — mirrors DynamicDataServiceImpl.delete's soft path.
                    Map<String, Object> softDelete = new HashMap<>();
                    softDelete.put("deleted_flag", true);
                    softDelete.put("updated_at", Instant.now());
                    deleted = executeScopedUpdate(tableName, targetModel, delIdEntry, softDelete, tenantId, Set.of());
                } else {
                    deleted = executeScopedDelete(tableName, targetModel, delIdEntry, tenantId);
                }
                results.put(targetModel + "_deleted", deleted);
            } else {
                // Defense: reject delete/update without targetRecordPid.
                // Without this guard the code falls through to INSERT and silently
                // creates a blank row (only DB NOT NULL constraints catch it, by accident).
                if ("delete".equalsIgnoreCase(operationType)) {
                    throw new BusinessException(ResponseCode.BadParam,
                            "targetRecordPid is required for delete operations");
                }
                if ("update".equalsIgnoreCase(operationType)) {
                    throw new BusinessException(ResponseCode.BadParam,
                            "targetRecordPid is required for update operations");
                }
                // Default: INSERT - generate pid and set audit timestamps for new records
                String newPid = UniqueIdGenerator.generate();
                Instant now = Instant.now();
                columnData.put("pid", newPid);
                columnData.putIfAbsent("created_at", now);
                columnData.putIfAbsent("updated_at", now);
                // Populate record ownership so row-level data scoping (self/department)
                // applies to command-created records (gap #7: command path previously
                // left created_by NULL, making scoped records un-ownable).
                Long auditUserId = MetaContext.exists() ? MetaContext.getCurrentUserId() : null;
                if (auditUserId != null) {
                    columnData.putIfAbsent("created_by", auditUserId);
                    columnData.putIfAbsent("updated_by", auditUserId);
                }
                int inserted = jsonbCols.isEmpty()
                        ? dynamicDataMapper.insert(tableName, columnData)
                        : dynamicDataMapper.insertWithJsonb(tableName, columnData, jsonbCols);
                results.put(targetModel + "_inserted", inserted);
                results.put("recordPid", newPid);
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
        Set<String> userMappedFields = new LinkedHashSet<>();
        for (String fieldCode : (inputFields != null ? inputFields : List.<String>of())) {
            Object value = payload.get(fieldCode);
            if (value != null) {
                // Skip virtual/readonly fields
                if (modelDef != null && isVirtualReadonlyField(modelDef, fieldCode)) {
                    continue;
                }
                data.put(fieldCode, value);
                userMappedFields.add(fieldCode);
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

        enforceEditableFields(modelCode, modelDef, userMappedFields);
        applyReferencePidCompanions(modelDef, data);

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
        Set<String> jsonbColumns = resolveJsonbColumns(modelDef, tableName);

        // For UPDATE: load existing JSONB data to preserve unmodified keys
        boolean isUpdateLike = "update".equalsIgnoreCase(operationType) || "state_transition".equalsIgnoreCase(operationType);
        if (isUpdateLike && StringUtils.hasText(request.getTargetRecordId()) && !jsonbColumns.isEmpty()) {
            injectExistingJsonbData(tableName, request.getTargetRecordId(), tenantId, jsonbColumns, data);
        }

        Map<String, Object> columnData = prepareColumnData(modelDef, data, jsonbColumns);

        // Execute the database operation
        if (isUpdateLike && StringUtils.hasText(request.getTargetRecordId())) {
            // F-5 fix: UPDATE must refresh updated_at — DDL DEFAULT CURRENT_TIMESTAMP only
            // applies to INSERT, so without explicit assignment audit timestamps stale forever.
            columnData.put("updated_at", Instant.now());
            var idEntry = CommandExecutorUtils.resolveRecordIdColumn(request.getTargetRecordId());
            int updated = executeScopedUpdate(tableName, modelCode, idEntry, columnData, tenantId, jsonbColumns);
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
            var delIdEntry = CommandExecutorUtils.resolveRecordIdColumn(request.getTargetRecordId());
            int deleted;
            if (modelDef != null && modelDef.isSoftDelete()) {
                // Soft-delete model: flag the row (recoverable/auditable) instead of
                // physically deleting — mirrors DynamicDataServiceImpl.delete's soft path.
                Map<String, Object> softDelete = new HashMap<>();
                softDelete.put("deleted_flag", true);
                softDelete.put("updated_at", Instant.now());
                deleted = executeScopedUpdate(tableName, modelCode, delIdEntry, softDelete, tenantId, Set.of());
            } else {
                deleted = executeScopedDelete(tableName, modelCode, delIdEntry, tenantId);
            }
            results.put(modelCode + "_deleted", deleted);
            log.info("Implicit FIELD_MAP DELETE: {} rows in {} (command={})", deleted, modelCode, command.getCode());
        } else {
            // Defense: reject delete/update without targetRecordPid.
            // Without this guard the code falls through to INSERT and silently
            // creates a blank row (only DB NOT NULL constraints catch it, by accident).
            if ("delete".equalsIgnoreCase(operationType)) {
                throw new BusinessException(ResponseCode.BadParam,
                        "targetRecordPid is required for delete operations");
            }
            if ("update".equalsIgnoreCase(operationType) || "state_transition".equalsIgnoreCase(operationType)) {
                throw new BusinessException(ResponseCode.BadParam,
                        "targetRecordPid is required for update operations");
            }
            // INSERT: generate pid and set audit timestamps for new records
            String newPid = UniqueIdGenerator.generate();
            java.time.Instant now = java.time.Instant.now();
            columnData.put("pid", newPid);
            columnData.putIfAbsent("created_at", now);
            columnData.putIfAbsent("updated_at", now);
            // Populate record ownership so row-level data scoping applies to
            // command-created records (gap #7).
            Long auditUserId = MetaContext.exists() ? MetaContext.getCurrentUserId() : null;
            if (auditUserId != null) {
                columnData.putIfAbsent("created_by", auditUserId);
                columnData.putIfAbsent("updated_by", auditUserId);
            }
            int inserted = jsonbColumns.isEmpty()
                    ? dynamicDataMapper.insert(tableName, columnData)
                    : dynamicDataMapper.insertWithJsonb(tableName, columnData, jsonbColumns);
            results.put(modelCode + "_inserted", inserted);
            results.put("recordPid", newPid);
            log.info("Implicit FIELD_MAP INSERT: {} rows in {} (pid={}, command={})", inserted, modelCode, newPid, command.getCode());
        }

        return results;
    }

    private int executeScopedUpdate(
            String tableName,
            String modelCode,
            Map.Entry<String, Object> idEntry,
            Map<String, Object> data,
            Long tenantId,
            Set<String> jsonbColumns) {
        if (data == null || data.isEmpty()) {
            return 0;
        }
        CommandExecutorUtils.validateSqlIdentifier(tableName, "FIELD_MAP update table");
        CommandExecutorUtils.validateSqlIdentifier(idEntry.getKey(), "FIELD_MAP update id column");

        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("UPDATE ")
                .append(tableName)
                .append(" SET ");
        int index = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            CommandExecutorUtils.validateSqlIdentifier(entry.getKey(), "FIELD_MAP update column");
            if (index > 0) {
                sql.append(", ");
            }
            String paramName = "set" + index;
            sql.append(entry.getKey()).append(" = #{params.").append(paramName).append("}");
            if (jsonbColumns != null && jsonbColumns.contains(entry.getKey())) {
                sql.append("::jsonb");
            }
            params.put(paramName, entry.getValue());
            index++;
        }

        params.put("recordId", idEntry.getValue());
        params.put("tenantId", tenantId);
        sql.append(" WHERE ")
                .append(idEntry.getKey())
                .append(" = #{params.recordId}")
                .append(" AND tenant_id = #{params.tenantId}");
        appendScopedWriteGuards(sql, tenantId, modelCode, "update");

        return dynamicDataMapper.updateByQuery(sql.toString(), params);
    }

    private int executeScopedDelete(
            String tableName,
            String modelCode,
            Map.Entry<String, Object> idEntry,
            Long tenantId) {
        CommandExecutorUtils.validateSqlIdentifier(tableName, "FIELD_MAP delete table");
        CommandExecutorUtils.validateSqlIdentifier(idEntry.getKey(), "FIELD_MAP delete id column");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("recordId", idEntry.getValue());
        params.put("tenantId", tenantId);
        StringBuilder sql = new StringBuilder("DELETE FROM ")
                .append(tableName)
                .append(" WHERE ")
                .append(idEntry.getKey())
                .append(" = #{params.recordId}")
                .append(" AND tenant_id = #{params.tenantId}");
        appendScopedWriteGuards(sql, tenantId, modelCode, "delete");

        return dynamicDataMapper.deleteByQuery(sql.toString(), params);
    }

    private void appendScopedWriteGuards(StringBuilder sql, Long tenantId, String modelCode, String operation) {
        if (MetaContext.isDataPermissionBypassed() || !MetaContext.exists()) {
            return;
        }
        Long userId = MetaContext.getCurrentUserId();
        if (userId == null) {
            return;
        }

        try {
            String rowFilter = DynamicDataQueryScope.rowFilter(tenantId, modelCode, userId,
                    () -> dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId));
            appendScopedFilter(sql, rowFilter);
        } catch (Exception e) {
            log.error("Failed to apply row-level data permission for FIELD_MAP {} on model {}",
                    operation, modelCode, e);
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Data permission evaluation failed for model: " + modelCode);
        }

        try {
            String domainFilter = DynamicDataQueryScope.domainFilter(tenantId, modelCode, userId,
                    () -> dataDomainService.buildDomainFilter(modelCode, userId));
            appendScopedFilter(sql, domainFilter);
        } catch (Exception e) {
            log.error("Failed to apply domain filter for FIELD_MAP {} on model {}",
                    operation, modelCode, e);
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Data domain filter evaluation failed for model: " + modelCode);
        }
    }

    private void appendScopedFilter(StringBuilder sql, String filter) {
        if (filter == null || filter.isBlank()) {
            return;
        }
        String normalized = filter.trim();
        if (normalized.regionMatches(true, 0, "AND ", 0, 4)) {
            normalized = normalized.substring(4).trim();
        } else if (normalized.regionMatches(true, 0, "WHERE ", 0, 6)) {
            normalized = normalized.substring(6).trim();
        }
        if (normalized.isBlank()) {
            return;
        }
        rejectStatementInjectionMarkers(normalized);
        sql.append(" AND ").append(normalized);
    }

    private void rejectStatementInjectionMarkers(String filter) {
        if (filter.contains(";") || filter.contains("--") || filter.contains("/*") || filter.contains("*/")) {
            throw new BusinessException(ResponseCode.BadParam, "Unsafe DataScope filter for FIELD_MAP write");
        }
    }

    private String resolveOperationType(Map<String, Object> execConfig, CommandExecuteRequest request) {
        String operationType = request != null ? request.getOperationType() : null;
        if (StringUtils.hasText(operationType)) {
            return operationType;
        }
        Object type = execConfig != null ? execConfig.get("type") : null;
        return type instanceof String ? (String) type : null;
    }

    private void enforceEditableFields(String modelCode, ModelDefinition modelDef, Set<String> userMappedFields) {
        if (modelDef == null || modelDef.getFields() == null || modelDef.getFields().isEmpty()
                || userMappedFields == null || userMappedFields.isEmpty() || !MetaContext.exists()) {
            return;
        }
        Long memberId = MetaContext.getCurrentMemberId();
        if (memberId == null) {
            return;
        }

        FieldPermissionSet permissionSet = fieldPermissionService.getFieldPermissions(memberId, modelCode);
        if (permissionSet == null || permissionSet.editableFields() == null) {
            return;
        }

        Set<String> blockedFields = userMappedFields.stream()
                .filter(StringUtils::hasText)
                .filter(fieldKey -> !isSystemField(fieldKey))
                .map(fieldKey -> resolveFieldCode(modelDef, fieldKey))
                .filter(Objects::nonNull)
                .filter(fieldCode -> !permissionSet.editableFields().contains(fieldCode))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!blockedFields.isEmpty()) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Field edit permission denied for model " + modelCode + ": " + String.join(", ", blockedFields));
        }
    }

    private boolean isSystemField(String fieldKey) {
        return "tenant_id".equals(fieldKey)
                || "pid".equals(fieldKey)
                || "created_at".equals(fieldKey)
                || "created_by".equals(fieldKey)
                || "updated_at".equals(fieldKey)
                || "updated_by".equals(fieldKey);
    }

    private String resolveFieldCode(ModelDefinition modelDef, String fieldKey) {
        if (modelDef == null || modelDef.getFields() == null || !StringUtils.hasText(fieldKey)) {
            return fieldKey;
        }
        for (FieldDefinition field : modelDef.getFields()) {
            if (fieldKey.equals(field.getCode()) || fieldKey.equals(field.getColumnName())) {
                return field.getCode();
            }
        }
        return fieldKey;
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

    private void applyReferencePidCompanions(ModelDefinition modelDef, Map<String, Object> data) {
        if (modelDef == null || modelDef.getFields() == null || data == null || data.isEmpty()) {
            return;
        }
        Set<String> fieldCodes = modelDef.getFields().stream()
                .map(FieldDefinition::getCode)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        for (FieldDefinition field : modelDef.getFields()) {
            String fieldCode = field.getCode();
            if (!StringUtils.hasText(fieldCode)
                    || !"reference".equalsIgnoreCase(field.getDataType())
                    || !fieldCode.endsWith("_id")
                    || data.containsKey(fieldCode)) {
                continue;
            }
            String pidField = fieldCode.substring(0, fieldCode.length() - 3) + "_pid";
            Object pidValue = data.get(pidField);
            if (fieldCodes.contains(pidField) && pidValue != null && StringUtils.hasText(String.valueOf(pidValue))) {
                data.put(fieldCode, pidValue);
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
    private Map<String, Object> prepareColumnData(ModelDefinition modelDef, Map<String, Object> data,
                                                   Set<String> jsonbColumns) {
        if (modelDef == null || modelDef.getFields() == null) {
            return data; // No model definition — pass through as-is
        }

        // Step 1: Merge JSONB virtual fields into host columns
        Map<String, Object> merged = JsonbFieldHelper.mergeJsonbFields(modelDef, data);

        // Step 2: Map field codes to column names, serialize JSONB
        Map<String, Object> columnData = new LinkedHashMap<>();
        Map<String, String> codeToColumn = new HashMap<>();
        Set<String> hostColumns = new LinkedHashSet<>(JsonbFieldHelper.getJsonbHostColumns(modelDef));
        if (jsonbColumns != null) {
            hostColumns.addAll(jsonbColumns);
        }
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
                if (hostColumns.contains(columnName) && JsonbFieldHelper.shouldSerializeJsonValue(value)) {
                    columnData.put(columnName, JsonbFieldHelper.toJsonString(value));
                } else {
                    columnData.put(columnName, value);
                }
            } else if (hostColumns.contains(key)) {
                columnData.put(key, JsonbFieldHelper.shouldSerializeJsonValue(value)
                        ? JsonbFieldHelper.toJsonString(value)
                        : value);
            } else {
                // Unknown field — pass through (might be a system field not in the list)
                columnData.put(key, value);
            }
        }
        return columnData;
    }

    private Set<String> resolveJsonbColumns(ModelDefinition modelDef, String tableName) {
        Set<String> jsonbColumns = new LinkedHashSet<>();
        if (modelDef != null) {
            jsonbColumns.addAll(JsonbFieldHelper.getJsonbHostColumns(modelDef));
        }
        if (StringUtils.hasText(tableName)) {
            try {
                Set<String> physicalColumns = dynamicDataMapper.findJsonbColumns(tableName);
                if (physicalColumns != null) {
                    jsonbColumns.addAll(physicalColumns);
                }
            } catch (Exception e) {
                log.debug("Could not resolve physical JSONB columns for {}: {}", tableName, e.getMessage());
            }
        }
        return jsonbColumns;
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
