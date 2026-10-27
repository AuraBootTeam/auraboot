package com.auraboot.framework.agent.util;

import com.auraboot.framework.meta.dto.QueryCondition;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical QueryCondition vocabulary shared by discovered and declared list tools. */
public final class DslListFilterSchema {
    private DslListFilterSchema() {}

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
