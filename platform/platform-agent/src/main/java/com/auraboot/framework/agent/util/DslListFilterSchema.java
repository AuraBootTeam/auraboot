package com.auraboot.framework.agent.util;

import com.auraboot.framework.meta.dto.QueryCondition;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;

/** Canonical QueryCondition vocabulary shared by discovered and declared list tools. */
public final class DslListFilterSchema {
    private DslListFilterSchema() {}

    /** Metadata belongs to the authorized model, never to sampled business records. */
    public static Map<String, Object> forModel(DynamicDataMapper mapper, Long tenantId, String modelCode) {
        List<Map<String, Object>> rows = mapper.selectByQuery(
                "SELECT f.code, f.data_type FROM ab_meta_field f "
                + "JOIN ab_meta_model_field_binding b ON b.field_id = f.id "
                + "JOIN ab_meta_model m ON m.id = b.model_id "
                + "WHERE m.tenant_id = #{params.tenantId} AND m.code = #{params.modelCode} "
                + "AND (m.deleted_flag = FALSE OR m.deleted_flag IS NULL) "
                + "AND (f.deleted_flag = FALSE OR f.deleted_flag IS NULL) "
                + "AND (b.deleted_flag = FALSE OR b.deleted_flag IS NULL) "
                + "AND (m.is_current = TRUE OR m.is_current IS NULL) "
                + "AND (f.is_current = TRUE OR f.is_current IS NULL) ORDER BY b.field_order",
                Map.of("tenantId", tenantId, "modelCode", modelCode));
        List<String> codes = new java.util.ArrayList<>(List.of("pid", "id", "created_at", "updated_at"));
        List<String> descriptions = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!(row.get("code") instanceof String code) || code.isBlank()) continue;
            if (!codes.contains(code)) codes.add(code);
            descriptions.add(code + " (" + row.getOrDefault("data_type", "text") + ")");
        }
        Map<String, Object> schema = new LinkedHashMap<>(filters());
        if (!descriptions.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>)
                    ((Map<?, ?>) schema.get("items")).get("properties"));
            properties.put("fieldName", Map.of("type", "string", "enum", codes,
                    "description", "Exact field code from this model. Never guess a related model's prefix."));
            schema.put("items", Map.of("type", "object", "properties", properties));
            schema.put("description", schema.get("description") + " Available fields: "
                    + String.join(", ", descriptions) + ". Filter related records by their actual reference field and the selected record pid.");
        }
        return schema;
    }

    public static Map<String, Object> filters() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("fieldName", Map.of("type", "string", "description", "Exact DSL field code; use fieldName, not field."));
        fields.put("operator", Map.of("type", "string", "enum", Arrays.stream(QueryCondition.Operator.values()).map(Enum::name).toList()));
        fields.put("value", Map.of("description", "Comparison value for a scalar operator."));
        fields.put("values", Map.of("type", "array", "items", Map.of(), "description", "Values for IN, NOT_IN, BETWEEN and NOT_BETWEEN."));
        fields.put("logicalOperator", Map.of("type", "string", "enum", java.util.List.of("AND", "OR")));
        fields.put("subConditions", Map.of("type", "array", "items", Map.of("type", "object"),
                "description", "Nested conditions using the same fieldName/operator/value(s) shape, or further subConditions."));
        return Map.of("type", "array", "items", Map.of("type", "object", "properties", fields),
                "description", "Leaf conditions use fieldName and operator. Groups use subConditions and logicalOperator. Record values are not field codes.");
    }
}
