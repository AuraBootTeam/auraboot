package com.auraboot.framework.meta.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import java.util.Locale;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure parsing/validation helpers for named-query SQL assembly — extracted
 * from NamedQueryServiceImpl (2026-11-14 three-way split, step 1) so the
 * parsing layer is independently unit-testable and reusable by the execution
 * engine and export services. No Spring beans, no instance state.
 */
public final class NamedQueryQueryParseUtils {

    private NamedQueryQueryParseUtils() {
    }

    public static final Set<String> ALLOWED_OPERATORS = Set.of(
            "eq", "ne", "gt", "gte", "lt", "lte", "like", "ilike",
            "in", "not_in", "is_null", "is_not_null", "between",
            "starts_with", "ends_with", "contains"
    );

    public static final Set<String> PUBLIC_FORBIDDEN_OUTPUT_ALIASES = Set.of(
            "id", "record_id", "tenant_id", "created_by", "updated_by"
    );

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static void parseBaseWhere(JsonNode baseWhere, List<String> whereClauses, Map<String, Object> params) {
        if (baseWhere == null || !baseWhere.isArray()) {
            return;
        }
        int idx = 0;
        for (JsonNode condition : baseWhere) {
            String field = condition.has("field") ? condition.get("field").asText() : null;
            String operator = condition.has("operator") ? condition.get("operator").asText() : "eq";
            JsonNode valueNode = condition.has("value") ? condition.get("value") : null;

            if (field == null || valueNode == null) {
                continue;
            }

            // Validate field name to prevent SQL injection via baseWhere config
            SqlSafetyUtils.validateIdentifier(field, "baseWhere field");

            if (!ALLOWED_OPERATORS.contains(operator.toLowerCase())) {
                throw new MetaServiceException("Unsupported operator in baseWhere: " + operator);
            }

            String paramKey = "bw_" + idx;
            String clause = buildWhereClause(field, operator, paramKey, valueNode, params);
            if (clause != null) {
                whereClauses.add(clause);
            }
            idx++;
        }
    }

    public static String parseOrderConditions(JsonNode orderConditions, Map<String, NamedQueryField> fieldMap) {
        if (orderConditions == null || !orderConditions.isArray()) {
            return "";
        }
        List<String> orderParts = new ArrayList<>();
        for (JsonNode order : orderConditions) {
            String fieldCode = order.has("field") ? order.get("field").asText() : null;
            String direction = order.has("direction") ? order.get("direction").asText() : "asc";

            if (fieldCode == null) continue;

            NamedQueryField fieldDef = fieldMap.get(fieldCode);
            if (fieldDef == null || !fieldDef.checkSortable()) {
                continue; // Skip non-sortable fields silently
            }

            String dir = "desc".equalsIgnoreCase(direction) ? "desc" : "asc";
            orderParts.add(fieldDef.getColumnExpr() + " " + dir);
        }
        return String.join(", ", orderParts);
    }

    public static String parseDefaultOrder(JsonNode defaultOrder, Map<String, NamedQueryField> fieldMap) {
        // defaultOrder can be array format: [{"field":"name","direction":"asc"}]
        if (defaultOrder.isArray()) {
            return parseOrderConditions(defaultOrder, fieldMap);
        }
        // Or object format: {"field":"created_at","direction":"desc"}
        if (defaultOrder.isObject() && defaultOrder.has("field")) {
            String fieldCode = defaultOrder.get("field").asText();
            String direction = defaultOrder.has("direction") ? defaultOrder.get("direction").asText() : "asc";
            NamedQueryField fieldDef = fieldMap.get(fieldCode);
            if (fieldDef != null && fieldDef.checkSortable()) {
                String dir = "desc".equalsIgnoreCase(direction) ? "desc" : "asc";
                return fieldDef.getColumnExpr() + " " + dir;
            }
        }
        return "";
    }

    public static String mapSortColumn(String sortBy) {
        switch (sortBy) {
            case "createdAt": return "created_at";
            case "updatedAt": return "updated_at";
            case "code": return "code";
            case "title": return "title";
            case "status": return "status";
            default: return "created_at";
        }
    }

    public static Object extractValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            if (node.isInt()) return node.intValue();
            if (node.isLong()) return node.longValue();
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    public static String quotedIdentifier(String fieldCode) {
        return "\"" + fieldCode + "\"";
    }

    public static void validatePublicOutputAliases(List<NamedQueryField> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        for (NamedQueryField field : fields) {
            validatePublicOutputAlias(field.getFieldCode());
        }
    }

    public static void validatePublicOutputAlias(String fieldCode) {
        if (fieldCode == null) {
            return;
        }
        String normalized = fieldCode.trim().toLowerCase(Locale.ROOT);
        if (PUBLIC_FORBIDDEN_OUTPUT_ALIASES.contains(normalized)) {
            throw new MetaServiceException("NamedQuery public output field alias is reserved for internal identity: " + fieldCode);
        }
    }

    public static void parseUserConditions(JsonNode conditions, Map<String, NamedQueryField> fieldMap,
                                     List<String> whereClauses, Map<String, Object> params) {
        if (conditions == null || !conditions.isArray()) {
            return;
        }
        int idx = 0;
        for (JsonNode condition : conditions) {
            String fieldCode = condition.has("field") ? condition.get("field").asText() : null;
            String operator = condition.has("operator") ? condition.get("operator").asText() : "eq";
            JsonNode valueNode = condition.has("value") ? condition.get("value") : null;

            if (fieldCode == null) {
                continue;
            }

            // Validate field is in whitelist
            NamedQueryField fieldDef = fieldMap.get(fieldCode);
            if (fieldDef == null) {
                throw new MetaServiceException("Field not allowed: " + fieldCode);
            }

            // Validate operator is allowed
            if (fieldDef.hasOperators() && !fieldDef.supportsOperator(operator)) {
                throw new MetaServiceException("Operator not allowed for field " + fieldCode + ": " + operator);
            }

            String columnExpr = fieldDef.getColumnExpr();
            // Validate columnExpr to prevent SQL injection via admin-configured field definitions
            SqlSafetyUtils.validateSqlFragment(columnExpr);
            String paramKey = "uc_" + idx;
            String clause = buildWhereClause(columnExpr, operator, paramKey, valueNode, params);
            if (clause != null) {
                whereClauses.add(clause);
            }
            idx++;
        }
    }

    public static String buildWhereClause(String column, String operator, String paramKey,
                                    JsonNode valueNode, Map<String, Object> params) {
        String op = operator.toLowerCase();
        switch (op) {
            case "eq":
                params.put(paramKey, extractValue(valueNode));
                return column + " = #{params." + paramKey + "}";
            case "ne":
                params.put(paramKey, extractValue(valueNode));
                return column + " != #{params." + paramKey + "}";
            case "gt":
                params.put(paramKey, extractValue(valueNode));
                return column + " > #{params." + paramKey + "}";
            case "gte":
                params.put(paramKey, extractValue(valueNode));
                return column + " >= #{params." + paramKey + "}";
            case "lt":
                params.put(paramKey, extractValue(valueNode));
                return column + " < #{params." + paramKey + "}";
            case "lte":
                params.put(paramKey, extractValue(valueNode));
                return column + " <= #{params." + paramKey + "}";
            case "like":
            case "contains":
                params.put(paramKey, "%" + extractValue(valueNode) + "%");
                return column + " LIKE #{params." + paramKey + "}";
            case "ilike":
                params.put(paramKey, "%" + extractValue(valueNode) + "%");
                return column + " ILIKE #{params." + paramKey + "}";
            case "starts_with":
                params.put(paramKey, extractValue(valueNode) + "%");
                return column + " LIKE #{params." + paramKey + "}";
            case "ends_with":
                params.put(paramKey, "%" + extractValue(valueNode));
                return column + " LIKE #{params." + paramKey + "}";
            case "is_null":
                return column + " IS NULL";
            case "is_not_null":
                return column + " IS NOT NULL";
            case "in":
                if (valueNode.isArray()) {
                    List<Object> values = new ArrayList<>();
                    for (JsonNode v : valueNode) {
                        values.add(extractValue(v));
                    }
                    params.put(paramKey, values);
                    // Build IN clause with indexed params
                    StringBuilder inClause = new StringBuilder(column + " IN (");
                    for (int i = 0; i < values.size(); i++) {
                        String indexedKey = paramKey + "_" + i;
                        params.put(indexedKey, values.get(i));
                        if (i > 0) inClause.append(", ");
                        inClause.append("#{params.").append(indexedKey).append("}");
                    }
                    inClause.append(")");
                    return inClause.toString();
                }
                return null;
            case "not_in":
                if (valueNode.isArray()) {
                    List<Object> values = new ArrayList<>();
                    for (JsonNode v : valueNode) {
                        values.add(extractValue(v));
                    }
                    StringBuilder notInClause = new StringBuilder(column + " NOT IN (");
                    for (int i = 0; i < values.size(); i++) {
                        String indexedKey = paramKey + "_" + i;
                        params.put(indexedKey, values.get(i));
                        if (i > 0) notInClause.append(", ");
                        notInClause.append("#{params.").append(indexedKey).append("}");
                    }
                    notInClause.append(")");
                    return notInClause.toString();
                }
                return null;
            case "between":
                if (valueNode.isArray() && valueNode.size() >= 2) {
                    params.put(paramKey + "_from", extractValue(valueNode.get(0)));
                    params.put(paramKey + "_to", extractValue(valueNode.get(1)));
                    return column + " BETWEEN #{params." + paramKey + "_from} AND #{params." + paramKey + "_to}";
                }
                return null;
            default:
                throw new MetaServiceException("Unsupported operator: " + operator);
        }
    }

    public static List<Map<String, Object>> dropEmptyRowNullEntries(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows.stream().filter(Objects::nonNull).collect(Collectors.toList());
    }
}
