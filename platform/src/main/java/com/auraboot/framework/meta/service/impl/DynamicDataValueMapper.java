package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import java.util.*;

/** Maps declared metadata fields to physical storage values and query columns. */
final class DynamicDataValueMapper {
    private DynamicDataValueMapper() {}
    private static final Set<String> SYSTEM_COLUMNS = SystemFieldConstants.QUERY_TRANSPARENT;
    static Object convertFieldValue(FieldDefinition field, Object value) {
        if (value == null) {
            return null;
        }

        String dataType = field.getDataType();
        if (dataType == null) {
            return value;
        }

        switch (dataType.toUpperCase()) {
            case "DATE":
                if (value instanceof String) {
                    try {
                        return java.sql.Date.valueOf((String) value);
                    } catch (Exception e) {
                        throw new MetaServiceException(
                            "Invalid date value for field '" + field.getCode() + "': " + value, e);
                    }
                }
                return value;

            case "DATETIME":
            case "TIMESTAMP":
            case "LOCALDATETIME":
                if (value instanceof java.time.Instant instant) {
                    return java.sql.Timestamp.from(instant);
                }
                if (value instanceof java.time.LocalDateTime localDateTime) {
                    return java.sql.Timestamp.valueOf(localDateTime);
                }
                if (value instanceof String) {
                    try {
                        return java.sql.Timestamp.valueOf((String) value);
                    } catch (Exception e) {
                        throw new MetaServiceException(
                            "Invalid datetime value for field '" + field.getCode() + "': " + value, e);
                    }
                }
                return value;

            case "INTEGER":
                if (value instanceof String) {
                    try {
                        return Integer.valueOf((String) value);
                    } catch (NumberFormatException e) {
                        throw new MetaServiceException(
                            "Invalid integer value for field '" + field.getCode() + "': " + value);
                    }
                }
                return value;

            case "LONG":
                if (value instanceof String) {
                    try {
                        return Long.valueOf((String) value);
                    } catch (NumberFormatException e) {
                        throw new MetaServiceException(
                            "Invalid long value for field '" + field.getCode() + "': " + value);
                    }
                }
                return value;

            case "DECIMAL":
                if (value instanceof String) {
                    try {
                        return new java.math.BigDecimal((String) value);
                    } catch (NumberFormatException e) {
                        throw new MetaServiceException(
                            "Invalid decimal value for field '" + field.getCode() + "': " + value);
                    }
                }
                return value;

            case "BOOLEAN":
                if (value instanceof String) {
                    return Boolean.valueOf((String) value);
                }
                return value;

            default:
                return value;
        }
    }

    static Map<String, Object> toColumnData(ModelDefinition model, Map<String, Object> data) {
        // Step 1: Merge JSONB virtual fields into host columns
        Map<String, Object> mergedData = JsonbFieldHelper.mergeJsonbFields(model, data);

        // Step 2: Map field codes to column names (only for non-JSONB-virtual fields)
        Map<String, Object> columnData = new HashMap<>();
        Map<String, String> codeToColumn = new HashMap<>();
        Set<String> hostColumns = JsonbFieldHelper.getJsonbHostColumns(model);
        for (FieldDefinition field : model.getFields()) {
            if (!field.isJsonbVirtual()) {
                codeToColumn.put(field.getCode(), field.getColumnName());
                codeToColumn.put(field.getColumnName(), field.getColumnName());
            }
        }

        for (Map.Entry<String, Object> entry : mergedData.entrySet()) {
            String key = entry.getKey();
            if (SYSTEM_COLUMNS.contains(key)) {
                columnData.put(key, entry.getValue());
                continue;
            }
            String columnName = codeToColumn.get(key);
            if (columnName != null) {
                Object value = entry.getValue();
                // Serialize structured values for JSON/JSONB host columns.
                if (hostColumns.contains(columnName) && JsonbFieldHelper.shouldSerializeJsonValue(value)) {
                    columnData.put(columnName, JsonbFieldHelper.toJsonString(value));
                } else {
                    columnData.put(columnName, value);
                }
                continue;
            }
            // Could be a JSONB host column from mergeJsonbFields (key is already a column name)
            if (hostColumns.contains(key)) {
                Object value = entry.getValue();
                columnData.put(key, JsonbFieldHelper.shouldSerializeJsonValue(value) ? JsonbFieldHelper.toJsonString(value) : value);
                continue;
            }
            throw new MetaServiceException("Unknown field for model " + model.getCode() + ": " + key);
        }

        return columnData;
    }

    /**
     * toColumnData variant for UPDATE that preserves unmodified JSONB keys.
     */
    static Map<String, Object> toColumnDataForUpdate(ModelDefinition model, Map<String, Object> data, Map<String, Object> existingRecord) {
        // Step 1: Merge JSONB virtual fields, preserving unmodified keys from existing record
        Map<String, Object> mergedData = JsonbFieldHelper.mergeJsonbFieldsForUpdate(model, data, existingRecord);

        // Step 2: Same column mapping as toColumnData
        Map<String, Object> columnData = new HashMap<>();
        Map<String, String> codeToColumn = new HashMap<>();
        Set<String> hostColumns = JsonbFieldHelper.getJsonbHostColumns(model);
        for (FieldDefinition field : model.getFields()) {
            if (!field.isJsonbVirtual()) {
                codeToColumn.put(field.getCode(), field.getColumnName());
                codeToColumn.put(field.getColumnName(), field.getColumnName());
            }
        }

        for (Map.Entry<String, Object> entry : mergedData.entrySet()) {
            String key = entry.getKey();
            if (SYSTEM_COLUMNS.contains(key)) {
                columnData.put(key, entry.getValue());
                continue;
            }
            String columnName = codeToColumn.get(key);
            if (columnName != null) {
                Object value = entry.getValue();
                if (hostColumns.contains(columnName) && JsonbFieldHelper.shouldSerializeJsonValue(value)) {
                    columnData.put(columnName, JsonbFieldHelper.toJsonString(value));
                } else {
                    columnData.put(columnName, value);
                }
                continue;
            }
            if (hostColumns.contains(key)) {
                Object value = entry.getValue();
                columnData.put(key, JsonbFieldHelper.shouldSerializeJsonValue(value) ? JsonbFieldHelper.toJsonString(value) : value);
                continue;
            }
            throw new MetaServiceException("Unknown field for model " + model.getCode() + ": " + key);
        }

        return columnData;
    }

    static RelationDefinition findRelation(ModelDefinition model, String relationName) {
        if (model.getRelations() == null) {
            throw new MetaServiceException("Model " + model.getCode() + " has no relations defined");
        }
        return model.getRelations().stream()
                .filter(r -> relationName.equals(r.getName()))
                .findFirst()
                .orElseThrow(() -> new MetaServiceException(
                        "Relation '" + relationName + "' not found in model " + model.getCode()));
    }

    static FieldDefinition findFieldDefinition(ModelDefinition model, String fieldCode) {
        return model.getFields().stream()
                .filter(f -> fieldCode.equals(f.getCode()))
                .findFirst()
                .orElseThrow(() -> new MetaServiceException(
                        "Field '" + fieldCode + "' not found in model " + model.getCode()));
    }

    static String resolveColumnName(ModelDefinition model, String fieldName) {
        if (SYSTEM_COLUMNS.contains(fieldName) || "*".equals(fieldName)) {
            return fieldName;
        }
        for (FieldDefinition field : model.getFields()) {
            if (fieldName.equals(field.getCode()) || fieldName.equals(field.getColumnName())) {
                // JSONB virtual fields use their typed expression for WHERE/ORDER BY
                if (field.isJsonbVirtual()) {
                    return field.getJsonbFilterExpression();
                }
                return field.getColumnName();
            }
        }
        throw new MetaServiceException("Unknown field for model " + model.getCode() + ": " + fieldName);
    }

    static List<SortField> mapSortFields(ModelDefinition model, List<SortField> sortFields) {
        if (sortFields == null || sortFields.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, String> codeToColumn = new HashMap<>();
        for (FieldDefinition field : model.getFields()) {
            codeToColumn.put(field.getCode(), field.getColumnName());
            codeToColumn.put(field.getColumnName(), field.getColumnName());
        }

        List<SortField> mappedFields = new ArrayList<>();
        for (SortField sortField : sortFields) {
            String columnName = codeToColumn.get(sortField.getFieldName());
            if (columnName == null && !SYSTEM_COLUMNS.contains(sortField.getFieldName())) {
                throw new MetaServiceException("Unknown sort field for model " + model.getCode() + ": " + sortField.getFieldName());
            }
            mappedFields.add(SortField.builder()
                    .fieldName(SYSTEM_COLUMNS.contains(sortField.getFieldName()) ? sortField.getFieldName() : columnName)
                    .direction(sortField.getDirection())
                    .priority(sortField.getPriority())
                    .build());
        }
        return mappedFields;
    }

}
