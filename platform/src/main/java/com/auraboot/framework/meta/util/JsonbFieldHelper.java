package com.auraboot.framework.meta.util;

import com.auraboot.framework.common.util.JsonUtil;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;

import java.util.*;

/**
 * Helper for JSONB virtual field operations.
 * Handles merging virtual fields into host JSONB columns (write path)
 * and extracting virtual fields from JSONB columns (read path).
 */
public class JsonbFieldHelper {

    /**
     * Merge JSONB virtual field values into their host JSONB columns.
     * Called during INSERT/UPDATE to convert frontend field-level data into column-level data.
     *
     * Input:  { "subject": "Call", "call_duration": 30, "call_direction": "outbound" }
     * Output: { "subject": "Call", "ext": {"duration": 30, "direction": "outbound"} }
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mergeJsonbFields(ModelDefinition model, Map<String, Object> data) {
        if (model.getFields() == null || data == null) return new LinkedHashMap<>(data != null ? data : Map.of());

        // Collect JSONB virtual fields grouped by host column
        Map<String, List<FieldDefinition>> hostToVirtualFields = new LinkedHashMap<>();
        Set<String> virtualFieldCodes = new HashSet<>();
        for (FieldDefinition field : model.getFields()) {
            if (field.isJsonbVirtual()) {
                hostToVirtualFields
                        .computeIfAbsent(field.getJsonbColumn(), k -> new ArrayList<>())
                        .add(field);
                virtualFieldCodes.add(field.getCode());
            }
        }

        // No JSONB virtual fields — return as-is
        if (hostToVirtualFields.isEmpty()) {
            return new LinkedHashMap<>(data);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        // Map to accumulate JSONB objects per host column
        Map<String, Map<String, Object>> jsonbAccumulators = new LinkedHashMap<>();

        // Initialize accumulators with existing host column data (if provided)
        for (String hostColumn : hostToVirtualFields.keySet()) {
            Map<String, Object> existing = new LinkedHashMap<>();
            Object hostValue = data.get(hostColumn);
            if (hostValue instanceof Map) {
                existing.putAll((Map<String, Object>) hostValue);
            }
            jsonbAccumulators.put(hostColumn, existing);
        }

        // Process each data entry
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            if (virtualFieldCodes.contains(key)) {
                // Find the virtual field definition to get host column and path
                for (FieldDefinition field : model.getFields()) {
                    if (field.isJsonbVirtual() && key.equals(field.getCode())) {
                        jsonbAccumulators.get(field.getJsonbColumn())
                                .put(field.getJsonbPath(), entry.getValue());
                        break;
                    }
                }
            } else if (!hostToVirtualFields.containsKey(key)) {
                // Regular field — pass through (skip host column keys already handled via accumulators)
                result.put(key, entry.getValue());
            }
        }

        // Merge accumulated JSONB objects into result
        for (Map.Entry<String, Map<String, Object>> acc : jsonbAccumulators.entrySet()) {
            if (!acc.getValue().isEmpty()) {
                result.put(acc.getKey(), acc.getValue());
            }
        }

        return result;
    }

    /**
     * For UPDATE operations: merge new JSONB virtual field values with existing record data.
     * Reads the current JSONB column value from the existing record and merges new values in.
     * This preserves unmodified keys in the JSONB column.
     *
     * @param model         the model definition
     * @param updateData    the new field values being updated
     * @param existingRecord the current record from database (with JSONB fields already extracted)
     * @return merged column data ready for SQL UPDATE
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mergeJsonbFieldsForUpdate(
            ModelDefinition model, Map<String, Object> updateData, Map<String, Object> existingRecord) {
        if (model.getFields() == null || updateData == null) return new LinkedHashMap<>(updateData != null ? updateData : Map.of());

        // Collect JSONB virtual fields grouped by host column
        Map<String, List<FieldDefinition>> hostToVirtualFields = new LinkedHashMap<>();
        Set<String> virtualFieldCodes = new HashSet<>();
        for (FieldDefinition field : model.getFields()) {
            if (field.isJsonbVirtual()) {
                hostToVirtualFields
                        .computeIfAbsent(field.getJsonbColumn(), k -> new ArrayList<>())
                        .add(field);
                virtualFieldCodes.add(field.getCode());
            }
        }

        if (hostToVirtualFields.isEmpty()) {
            return new LinkedHashMap<>(updateData);
        }

        // Check if any virtual fields are being updated
        boolean hasVirtualFieldUpdate = updateData.keySet().stream().anyMatch(virtualFieldCodes::contains);
        if (!hasVirtualFieldUpdate) {
            return new LinkedHashMap<>(updateData);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Map<String, Object>> jsonbAccumulators = new LinkedHashMap<>();

        // Initialize accumulators from EXISTING record's virtual fields (to preserve unmodified keys)
        for (Map.Entry<String, List<FieldDefinition>> entry : hostToVirtualFields.entrySet()) {
            Map<String, Object> existing = new LinkedHashMap<>();
            for (FieldDefinition vf : entry.getValue()) {
                Object existingValue = existingRecord != null ? existingRecord.get(vf.getCode()) : null;
                if (existingValue != null) {
                    existing.put(vf.getJsonbPath(), existingValue);
                }
            }
            jsonbAccumulators.put(entry.getKey(), existing);
        }

        // Apply new values (overwrite existing keys, add new keys)
        for (Map.Entry<String, Object> entry : updateData.entrySet()) {
            String key = entry.getKey();
            if (virtualFieldCodes.contains(key)) {
                for (FieldDefinition field : model.getFields()) {
                    if (field.isJsonbVirtual() && key.equals(field.getCode())) {
                        jsonbAccumulators.get(field.getJsonbColumn())
                                .put(field.getJsonbPath(), entry.getValue());
                        break;
                    }
                }
            } else if (!hostToVirtualFields.containsKey(key)) {
                result.put(key, entry.getValue());
            }
        }

        // Merge accumulated JSONB objects into result
        for (Map.Entry<String, Map<String, Object>> acc : jsonbAccumulators.entrySet()) {
            if (!acc.getValue().isEmpty()) {
                result.put(acc.getKey(), acc.getValue());
            }
        }

        return result;
    }

    /**
     * Collect the set of JSONB host column names from a model definition.
     */
    public static Set<String> getJsonbHostColumns(ModelDefinition model) {
        if (model.getFields() == null) return Set.of();
        Set<String> hostColumns = new LinkedHashSet<>();
        for (FieldDefinition field : model.getFields()) {
            String dt = field.getDataType();
            if (("jsonb".equalsIgnoreCase(dt) || "json".equalsIgnoreCase(dt)) && !field.isJsonbVirtual()) {
                hostColumns.add(field.getColumnName());
            }
        }
        return hostColumns;
    }

    /**
     * Serialize a Map to a JSONB-compatible string for PostgreSQL.
     */
    public static String toJsonString(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        return JsonUtil.toJson(value);
    }
}
