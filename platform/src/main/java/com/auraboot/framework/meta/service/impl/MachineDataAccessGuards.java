package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.meta.service.QueryBuilderService;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import org.springframework.security.access.AccessDeniedException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Executes approved machine decisions using bound values at the data-access boundary. */
public final class MachineDataAccessGuards {
    private static final Set<String> INTERNAL_READ_FIELDS = Set.of("pid", "id", "tenant_id", "row_version");
    private MachineDataAccessGuards() { }

    public static void requireQueryFields(String modelCode, com.auraboot.framework.meta.dto.DynamicQueryRequest request) {
        if (!MachineDataAccessContext.isActive()) return;
        var decision = MachineDataAccessContext.requireDecision(modelCode);
        if (request.getViewId() != null || request.getKeyword() != null || request.getAuditUserDisplayFields() != null) {
            throw new AccessDeniedException("Machine queries require explicit published fields");
        }
        requireConditions(decision, request.getConditions(), 0);
        if (request.getKeysetContinuation() != null && !decision.readableFields().contains(request.getKeysetContinuation().field())) {
            throw new AccessDeniedException("Machine continuation field is not authorized");
        }
        if (request.getSortFields() != null) request.getSortFields().forEach(sort -> {
            if (!decision.readableFields().contains(sort.getFieldName())) throw new AccessDeniedException("Machine sort field is not authorized");
        });
    }

    private static void requireConditions(MachineDataAccessContext.Decision decision,
                                          java.util.List<com.auraboot.framework.meta.dto.QueryCondition> conditions, int depth) {
        if (conditions == null) return;
        if (depth > 8 || conditions.size() > 32) throw new AccessDeniedException("Bounded machine conditions required");
        for (var condition : conditions) {
            if (condition == null || (condition.getFieldName() != null
                    && !decision.readableFields().contains(condition.getFieldName()))) {
                throw new AccessDeniedException("Machine query field is not authorized");
            }
            requireConditions(decision, condition.getSubConditions(), depth + 1);
        }
    }

    public static void constrain(String modelCode, QueryBuilderService.QueryBuilder query) {
        if (!MachineDataAccessContext.isActive()) return;
        var decision = MachineDataAccessContext.requireDecision(modelCode);
        decision.constraints().forEach(constraint -> query.addCondition(constraint.column(), "IN", constraint.values()));
    }

    public static String predicate(String modelCode, String action, Map<String, Object> parameters, String prefix) {
        var decision = MachineDataAccessContext.requireDecision(modelCode);
        decision.requireAction(action);
        SqlSafetyUtils.requireIdentifier(prefix, "machine parameter prefix");
        StringBuilder sql = new StringBuilder("1 = 1");
        for (int index = 0; index < decision.constraints().size(); index++) {
            var constraint = decision.constraints().get(index);
            sql.append(" AND ").append(SqlSafetyUtils.requireIdentifier(constraint.column(), "machine policy column")).append(" IN (");
            for (int valueIndex = 0; valueIndex < constraint.values().size(); valueIndex++) {
                if (valueIndex > 0) sql.append(", ");
                String key = prefix + "_" + index + "_" + valueIndex;
                if (parameters.containsKey(key)) throw new AccessDeniedException("Reserved machine policy parameter");
                parameters.put(key, constraint.values().get(valueIndex));
                sql.append("#{params.").append(key).append("}");
            }
            sql.append(")");
        }
        return sql.toString();
    }

    public static Map<String, Object> filterFields(String modelCode, Map<String, Object> row) {
        var decision = MachineDataAccessContext.requireDecision(modelCode);
        Map<String, Object> filtered = new LinkedHashMap<>();
        row.forEach((field, value) -> {
            if (decision.readableFields().contains(field) || INTERNAL_READ_FIELDS.contains(field)
                    || decision.constraints().stream().anyMatch(constraint -> constraint.field().equals(field))) filtered.put(field, value);
        });
        return filtered;
    }

    public static void requireWritable(String modelCode, Map<String, Object> data) {
        var decision = MachineDataAccessContext.requireDecision(modelCode);
        for (String field : data.keySet()) {
            if (!decision.writableFields().contains(field) && !"_expectedVersion".equals(field)) {
                throw new AccessDeniedException("Machine field write is not authorized");
            }
        }
    }
}
