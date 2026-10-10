package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import java.util.List;
import java.util.Map;

/** Exact-command ownership for marked records in otherwise shared mutable models. */
public final class RecordCommandWriterGuard {
    private static final String POLICY = "recordCommandWriters";
    private RecordCommandWriterGuard() {}

    private record Policy(FieldDefinition marker, Map<?, ?> commands) {}

    private static Policy policy(ModelDefinition model) {
        Object raw = model == null || model.getExtension() == null ? null : model.getExtension().get(POLICY);
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> declaration) || !declaration.keySet().equals(java.util.Set.of("field", "commands"))
            || !(declaration.get("field") instanceof String code) || code.isBlank()
            || !(declaration.get("commands") instanceof Map<?, ?> commands)
            || !commands.keySet().equals(java.util.Set.of("create", "update", "delete"))) throw invalid(model);
        for (Object writers : commands.values()) {
            if (!(writers instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank())) throw invalid(model);
        }
        FieldDefinition marker = model.getFields() == null ? null : model.getFields().stream()
            .filter(f -> f != null && code.equals(f.getCode())).findFirst().orElse(null);
        if (marker == null || !"boolean".equalsIgnoreCase(marker.getDataType()) || marker.isVirtual() || marker.isJsonbVirtual()) throw invalid(model);
        SqlSafetyUtils.requireIdentifier(column(marker), "record writer marker column");
        return new Policy(marker, commands);
    }

    private static MetaServiceException invalid(ModelDefinition model) {
        return new MetaServiceException("Invalid recordCommandWriters policy on model: " + model.getCode());
    }
    private static String column(FieldDefinition marker) {
        return marker.getColumnName() == null || marker.getColumnName().isBlank() ? marker.getCode() : marker.getColumnName();
    }
    private static boolean authorized(Policy policy, String operation) {
        if (!policy.commands().containsKey(operation)) throw new MetaServiceException("Unsupported record writer operation: " + operation);
        return MetaContext.hasCommandPermitScope() && MetaContext.getAuthorizedCommandCode() != null
            && ((List<?>) policy.commands().get(operation)).contains(MetaContext.getAuthorizedCommandCode());
    }

    /** A caller cannot acquire ownership merely by setting the marker on an existing or new row. */
    public static void assertInputAllowed(ModelDefinition model, Map<String, Object> input, String operation) {
        Policy policy = policy(model);
        if (policy == null || input == null) return;
        for(String name: new java.util.LinkedHashSet<>(java.util.List.of(policy.marker().getCode(), column(policy.marker())))) {
            Object proposed=input.get(name);
            if (proposed != null && !(proposed instanceof Boolean)) throw new MetaServiceException("Record writer marker must be a boolean");
            if (Boolean.TRUE.equals(proposed) && !authorized(policy, operation)) throw denied(model);
        }
    }

    /** Read the persisted marker, never a merged caller payload, before producing effects. */
    public static void assertStoredAllowed(ModelDefinition model, Map<String, Object> stored, String operation) {
        Policy policy = policy(model);
        if (policy == null || stored == null) return;
        Object marker = stored.get(policy.marker().getCode());
        if (marker != null && !(marker instanceof Boolean)) throw new MetaServiceException("Invalid stored record writer marker");
        if (Boolean.TRUE.equals(marker) && !authorized(policy, operation)) throw denied(model);
    }

    /** Added to the final SQL WHERE to preserve ownership under concurrent marker changes. */
    public static void appendStoredPredicate(StringBuilder sql, ModelDefinition model, String operation) {
        Policy policy = policy(model);
        if (policy != null && !authorized(policy, operation)) sql.append(" AND ").append(column(policy.marker())).append(" IS DISTINCT FROM TRUE");
    }

    /** Legacy mapper mutations keep their existing mapping but lock and validate their exact targets. */
    public static Map<String,Object> guardLegacyConditions(
            com.auraboot.framework.meta.mapper.DynamicDataMapper mapper, ModelDefinition model,
            String table, Map<String,Object> conditions, String operation) {
        Policy policy = policy(model);
        if (policy == null) return conditions;
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new MetaServiceException("Record writer target checks require an active transaction");
        Long tenant = MetaContext.getCurrentTenantId();
        if (tenant == null) throw new MetaServiceException("Record writer target checks require tenant context");
        Map<String,Object> scoped = new java.util.LinkedHashMap<>(conditions);
        Object declaredTenant = scoped.putIfAbsent("tenant_id", tenant);
        if (declaredTenant != null && !tenant.toString().equals(declaredTenant.toString())) throw denied(model);
        if (authorized(policy, operation)) return scoped;
        StringBuilder sql = new StringBuilder("SELECT ").append(column(policy.marker())).append(" AS ")
            .append(SqlSafetyUtils.requireIdentifier(policy.marker().getCode(), "record writer marker field"))
            .append(" FROM ").append(SqlSafetyUtils.requireIdentifier(table,"record writer target table")).append(" WHERE ");
        Map<String,Object> params = new java.util.LinkedHashMap<>();
        int i=0;
        for(var condition:scoped.entrySet()) {
            if(i>0) sql.append(" AND ");
            sql.append(SqlSafetyUtils.requireIdentifier(condition.getKey(),"record writer condition column"));
            if(condition.getValue()==null) sql.append(" IS NULL");
            else {String key="condition"+i;sql.append(" = #{params.").append(key).append("}");params.put(key,condition.getValue());}
            i++;
        }
        sql.append(" ORDER BY pid FOR UPDATE");
        List<Map<String,Object>> rows = mapper.selectByQuery(sql.toString(),params);
        if(rows!=null) for(Map<String,Object> row:rows) assertStoredAllowed(model,row,operation);
        return scoped;
    }

    /** Junction changes must resolve every target in the current tenant before producing effects. */
    public static void guardRelationTargets(
            com.auraboot.framework.meta.mapper.DynamicDataMapper mapper, ModelDefinition model,
            String table, java.util.Collection<String> identities, String operation) {
        Policy policy = policy(model);
        if (policy == null) return;
        Long tenant = relationTenant();
        String sql = "SELECT " + column(policy.marker()) + " AS "
            + SqlSafetyUtils.requireIdentifier(policy.marker().getCode(), "record writer marker field")
            + " FROM " + SqlSafetyUtils.requireIdentifier(table, "record writer target table")
            + " WHERE tenant_id = #{params.tenantId} AND deleted_flag = FALSE"
            + " AND (id::text = #{params.identity} OR pid = #{params.identity}) ORDER BY pid FOR UPDATE";
        if (identities == null || identities.stream().anyMatch(identity -> identity == null || identity.isBlank()))
            throw new MetaServiceException("Record writer relation target is missing");
        for (String identity : new java.util.TreeSet<>(identities)) {
            List<Map<String,Object>> rows = mapper.selectByQuery(sql, Map.of("tenantId", tenant, "identity", identity));
            if (rows == null || rows.size() != 1)
                throw new MetaServiceException("Record writer relation target is missing or ambiguous in the current tenant");
            assertStoredAllowed(model, rows.get(0), operation);
        }
    }

    /** Lock existing junction rows, then validate their targets before a replacement delete. */
    public static void guardRelationReplacement(
            com.auraboot.framework.meta.mapper.DynamicDataMapper mapper, ModelDefinition model,
            String targetTable, String junctionTable, String sourceColumn, String targetColumn, String sourceIdentity) {
        if (policy(model) == null) return;
        Long tenant = relationTenant();
        String sql = "SELECT " + SqlSafetyUtils.requireIdentifier(targetColumn, "record writer junction target")
            + " AS target_identity FROM " + SqlSafetyUtils.requireIdentifier(junctionTable, "record writer junction table")
            + " WHERE " + SqlSafetyUtils.requireIdentifier(sourceColumn, "record writer junction source")
            + " = #{params.sourceIdentity} AND tenant_id = #{params.tenantId} ORDER BY " + targetColumn + " FOR UPDATE";
        List<Map<String,Object>> links = mapper.selectByQuery(sql, Map.of("sourceIdentity", sourceIdentity, "tenantId", tenant));
        java.util.List<String> identities = new java.util.ArrayList<>();
        if (links != null) for (Map<String,Object> link : links) {
            Object identity = link.get("target_identity");
            if (identity == null) throw new MetaServiceException("Record writer relation target is missing");
            identities.add(identity.toString());
        }
        guardRelationTargets(mapper, model, targetTable, identities, "update");
    }

    private static Long relationTenant() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new MetaServiceException("Record writer target checks require an active transaction");
        Long tenant = MetaContext.getCurrentTenantId();
        if (tenant == null) throw new MetaServiceException("Record writer target checks require tenant context");
        return tenant;
    }

    public static boolean hasPolicy(ModelDefinition model) { return policy(model) != null; }

    public static void assertBulkClaimAllowed(ModelDefinition model) {
        Policy policy = policy(model);
        if (policy != null && !authorized(policy, "update")) throw denied(model);
    }
    private static MetaServiceException denied(ModelDefinition model) {
        return new MetaServiceException("RECORD_WRITER_DENIED: marked records on '" + model.getCode() + "' require their exact authorized command");
    }
}
