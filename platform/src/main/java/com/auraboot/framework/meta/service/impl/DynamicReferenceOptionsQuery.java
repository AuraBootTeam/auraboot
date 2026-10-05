package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.base.BaseMetaService;

import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Owns reference option SQL and authorized display resolution. */
@Slf4j
@RequiredArgsConstructor
final class DynamicReferenceOptionsQuery extends BaseMetaService {
    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metadataService;
    private final DynamicDataReadSupport readSupport;
    private final java.util.function.Function<String, ModelDefinition> modelLookup;
    private record ReferenceOptionTarget(
            String targetModelCode,
            String targetTable,
            String valueColumn,
            String displayExpression,
            String displayAlias,
            String groupColumn) {
    }
    private String resolveSystemTable(String modelCode) { return readSupport.resolveSystemTable(modelCode); }
    private String[] resolveDisplayColumnExpression(Optional<ModelDefinition> model, String modelCode, String displayField) { return readSupport.resolveDisplayColumnExpression(model, modelCode, displayField); }
    private ModelDefinition getModelDefinition(String code) { return modelLookup.apply(code); }

    private DynamicDataReadSupport.ReferenceReadAccess evaluateReferenceReadAccess(Long tenantId, Long userId, String modelCode) {
    return readSupport.evaluateReferenceReadAccess(tenantId, userId, modelCode);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> rawRefTargetMap(FieldDefinition fieldDef) {
        Map<String, Object> extra = fieldDef == null ? null : fieldDef.getExtraProps();
        if (extra == null || extra.isEmpty()) {
            return null;
        }
        Object raw = extra.get("refTarget");
        if (!(raw instanceof Map<?, ?>) && extra.get("extension") instanceof Map<?, ?> extension) {
            raw = extension.get("refTarget");
        }
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    ReferenceOptionTarget resolveReferenceOptionTarget(FieldDefinition fieldDef) {
        FieldDefinition.RefTarget canonical = fieldDef == null ? null : fieldDef.getRefTarget();
        Map<String, Object> raw = rawRefTargetMap(fieldDef);

        String targetModelCode = firstText(
                canonical == null ? null : canonical.getTargetEntity(),
                readMapText(raw, "targetEntity", "targetModel", "targetModelCode", "modelCode", "refModelCode"));
        String targetTable = firstText(
                canonical == null ? null : canonical.getTargetTable(),
                readMapText(raw, "targetTable", "table"));
        String valueField = firstText(
                canonical == null ? null : canonical.getValueField(),
                readMapText(raw, "valueField", "targetValueField"),
                "pid");
        String displayField = firstText(
                canonical == null ? null : canonical.getDisplayField(),
                readMapText(raw, "displayField", "refDisplayField", "targetField", "fieldCode"),
                "name");

        if (!hasText(targetModelCode) && !hasText(targetTable)) {
            return null;
        }

        // System aliases are fixed platform tables, not meta models. Probing their known-absent
        // model definition for every reference field creates a negative-lookup N+1.
        Optional<ModelDefinition> targetModelOpt = hasText(targetModelCode)
                && resolveSystemTable(targetModelCode) == null
                ? metadataService.getModelDefinition(targetModelCode)
                : Optional.empty();
        String resolvedTargetTable = firstText(
                targetTable,
                targetModelOpt.map(ModelDefinition::getTableName).orElse(null),
                resolveSystemTable(targetModelCode));
        if (!hasText(resolvedTargetTable)) {
            return null;
        }

        String valueColumn = resolveReferenceValueColumn(targetModelOpt.orElse(null), valueField);
        String[] displayColumn = resolveDisplayColumnExpression(targetModelOpt, targetModelCode, displayField);
        String groupColumn = resolveReferenceValueColumn(
                targetModelOpt.orElse(null),
                firstText(readMapText(raw, "groupField"), "group_code"));
        return new ReferenceOptionTarget(
                targetModelCode,
                resolvedTargetTable,
                valueColumn,
                displayColumn[0],
                displayColumn[1],
                groupColumn);
    }

    String resolveReferenceValueColumn(ModelDefinition targetModel, String configuredValueField) {
        String valueField = hasText(configuredValueField) ? configuredValueField : "pid";
        if (targetModel != null && targetModel.getFields() != null) {
            for (FieldDefinition field : targetModel.getFields()) {
                String columnName = field.getColumnName() != null ? field.getColumnName() : field.getCode();
                if (valueField.equals(field.getCode()) || valueField.equals(columnName)) {
                    return columnName;
                }
            }
        }
        return valueField;
    }

    String readMapText(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    boolean hasText(String value) {
        return value != null && !value.isBlank();
    }


    @Transactional(readOnly = true)
    public List<FieldOption> getFieldOptions(String modelCode, String fieldCode, FieldOptionRequest optionRequest) {
        validateModelCode(modelCode);
        logOperation("getFieldOptions", modelCode, fieldCode);

        ModelDefinition model = getModelDefinition(modelCode);
        FieldDefinition fieldDef = DynamicDataValueMapper.findFieldDefinition(model, fieldCode);
        ReferenceOptionTarget target = resolveReferenceOptionTarget(fieldDef);
        if (target == null) {
            return Collections.emptyList();
        }

        // Security: validate SQL identifiers to prevent injection
        java.util.regex.Pattern NAME_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
        if (!NAME_PATTERN.matcher(target.targetTable()).matches()
                || !NAME_PATTERN.matcher(target.valueColumn()).matches()
                || !NAME_PATTERN.matcher(target.displayAlias()).matches()) {
            log.warn("Invalid SQL identifier in refTarget config: table={}, value={}, display={}",
                    logSafe(target.targetTable()), logSafe(target.valueColumn()), logSafe(target.displayAlias()));
            return Collections.emptyList();
        }

        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();
        int limit = optionRequest != null && optionRequest.getLimit() != null ? optionRequest.getLimit() : 50;
        int offset = optionRequest != null && optionRequest.getOffset() != null ? optionRequest.getOffset() : 0;

        DynamicDataReadSupport.ReferenceReadAccess targetAccess = evaluateReferenceReadAccess(
                tenantId, userId, target.targetModelCode());
        if (!targetAccess.allowed()) {
            throw new BusinessException(
                    ResponseCode.FORBIDDEN,
                    "Read permission is required for reference target model: "
                            + target.targetModelCode());
        }

        // Build query
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(target.valueColumn()).append(", ")
                .append(target.displayExpression()).append(" AS ").append(target.displayAlias());
        sql.append(" FROM ").append(target.targetTable());
        sql.append(" WHERE tenant_id = #{params.tenantId}");

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);

        if (!targetAccess.rowFilter().isBlank()) {
            sql.append(" ").append(targetAccess.rowFilter());
        }

        // Add keyword filter
        if (optionRequest != null && optionRequest.getKeyword() != null && !optionRequest.getKeyword().isBlank()) {
            sql.append(" AND ").append(target.displayExpression()).append(" ILIKE #{params.keyword}");
            params.put("keyword", "%" + optionRequest.getKeyword() + "%");
        }

        // Add group filter
        if (optionRequest != null && optionRequest.getGroup() != null && !optionRequest.getGroup().isBlank()) {
            String groupField = target.groupColumn();
            if (!hasText(groupField) || !NAME_PATTERN.matcher(groupField).matches()) {
                log.warn("Invalid SQL identifier for groupField: {}", logSafe(groupField));
                return Collections.emptyList();
            }
            sql.append(" AND ").append(groupField).append(" = #{params.groupValue}");
            params.put("groupValue", optionRequest.getGroup());
        }

        sql.append(" ORDER BY ").append(target.displayAlias());
        sql.append(" LIMIT ").append(limit);
        sql.append(" OFFSET ").append(offset);

        List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql.toString(), params);

        // Convert to FieldOption list
        List<FieldOption> options = new ArrayList<>();
        int sortOrder = offset;
        for (Map<String, Object> row : results) {
            options.add(FieldOption.builder()
                    .value(row.get(target.valueColumn()) != null ? row.get(target.valueColumn()).toString() : null)
                    .label(row.get(target.displayAlias()) != null ? row.get(target.displayAlias()).toString() : null)
                    .sortOrder(sortOrder++)
                    .build());
        }

        return options;
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}
