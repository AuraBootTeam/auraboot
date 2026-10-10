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
        try {
            com.auraboot.framework.meta.security.RecordCommandWriterDeclaration.validate(raw);
        } catch (IllegalArgumentException e) {
            throw invalid(model);
        }
        Map<?, ?> declaration = (Map<?, ?>) raw;
        String code = (String) declaration.get("field");
        Map<?, ?> commands = (Map<?, ?>) declaration.get("commands");
        FieldDefinition marker = model.getFields() == null ? null : model.getFields().stream()
            .filter(f -> f != null && code.equals(f.getCode())).findFirst().orElse(null);
        if (marker == null || !"boolean".equalsIgnoreCase(marker.getDataType()) || marker.isVirtual() || marker.isJsonbVirtual() || !marker.isImmutable()) throw invalid(model);
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
        assertMatchingAliases(policy, input);
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
        appendStoredPredicate(sql, model, operation, null);
    }

    private static void appendOwnStoredPredicate(StringBuilder sql, ModelDefinition model, String operation) {
        Policy policy = policy(model);
        if (policy != null && !authorized(policy, operation)) sql.append(" AND ").append(column(policy.marker())).append(" IS DISTINCT FROM TRUE");
    }

    private record Parent(ModelDefinition model, FieldDefinition link, Policy policy) {}

    private static Parent parent(ModelDefinition child, java.util.function.Function<String, ModelDefinition> resolver) {
        if (policy(child) == null) return null;
        Object parentCode = child.getExtension().get("parentModel");
        if (parentCode == null) return null;
        if (!(parentCode instanceof String code) || code.isBlank() || resolver == null)
            throw new MetaServiceException("Record writer parent requires model resolution");
        ModelDefinition model = resolver.apply(code);
        if (model == null || !code.equals(model.getCode()))
            throw new MetaServiceException("Record writer parent model is missing");
        Policy ownership = policy(model);
        if (ownership == null) return null;
        Object fieldCode = child.getExtension().get("parentField");
        FieldDefinition link = child.getFields().stream()
            .filter(f -> f != null && f.getCode().equals(fieldCode)).findFirst().orElse(null);
        if (link == null || link.isVirtual() || link.isJsonbVirtual() || link.getRefTarget() == null
                || !code.equals(link.getRefTarget().getTargetEntity())
                || (link.getRefTarget().getValueField() != null && !"pid".equals(link.getRefTarget().getValueField())))
            throw new MetaServiceException("Record writer parent must use a physical PID reference");
        SqlSafetyUtils.requireIdentifier(model.getTableName(), "record writer parent table");
        SqlSafetyUtils.requireIdentifier(child.getTableName(), "record writer child table");
        SqlSafetyUtils.requireIdentifier(column(link), "record writer parent reference column");
        return new Parent(model, link, ownership);
    }

    /** The submitted parent is locked by its stored PID, never by a display label or numeric alias. */
    public static void guardParentInput(com.auraboot.framework.meta.mapper.DynamicDataMapper mapper,
            ModelDefinition child, java.util.function.Function<String, ModelDefinition> resolver, Map<String,Object> input) {
        Parent parent = parent(child, resolver);
        if (parent == null || input == null) return;
        String code = parent.link().getCode();
        String physical = column(parent.link());
        if (input.containsKey(code) && input.containsKey(physical)
                && !java.util.Objects.equals(input.get(code), input.get(physical)))
            throw new MetaServiceException("Conflicting record writer parent aliases");
        Object identity = input.containsKey(code) ? input.get(code) : input.get(physical);
        if (identity == null) return;
        checkParent(mapper, parent, identity);
    }

    private static void checkParent(com.auraboot.framework.meta.mapper.DynamicDataMapper mapper, Parent parent, Object identity) {
        if (!(identity instanceof String pid) || pid.isBlank())
            throw new MetaServiceException("Record writer parent requires a stored PID");
        Long tenant = relationTenant();
        Map<String,Object> conditions = new java.util.LinkedHashMap<>();
        conditions.put("tenant_id", tenant);
        conditions.put("pid", pid);
        if (parent.model().isSoftDelete()) conditions.put("deleted_flag", false);
        List<Map<String,Object>> rows = mapper.selectRecordWriterTargetsForUpdate(parent.model().getTableName(),
                column(parent.policy().marker()), parent.policy().marker().getCode(), conditions);
        if (rows == null || rows.size() != 1)
            throw new MetaServiceException("Record writer parent is missing or ambiguous in the current tenant");
        assertStoredAllowed(parent.model(), rows.get(0), "update");
    }

    /** Protect the existing parent even when the child's own marker is false. */
    public static void appendStoredPredicate(StringBuilder sql, ModelDefinition model, String operation,
            java.util.function.Function<String, ModelDefinition> resolver) {
        appendOwnStoredPredicate(sql, model, operation);
        Parent parent = parent(model, resolver);
        if (parent == null || authorized(parent.policy(), "update")) return;
        sql.append(" AND NOT EXISTS (SELECT 1 FROM ").append(parent.model().getTableName())
            .append(" record_writer_parent WHERE record_writer_parent.pid = ").append(model.getTableName())
            .append('.').append(column(parent.link()))
            .append(" AND record_writer_parent.tenant_id = ").append(model.getTableName()).append(".tenant_id")
            .append(" AND record_writer_parent.").append(column(parent.policy().marker())).append(" IS TRUE)");
    }

    private static void guardStoredParents(com.auraboot.framework.meta.mapper.DynamicDataMapper mapper,
            ModelDefinition child, java.util.function.Function<String, ModelDefinition> resolver, Map<String,Object> conditions) {
        Parent parent = parent(child, resolver);
        if (parent == null) return;
        List<Map<String,Object>> rows = mapper.selectRecordWriterTargetsForUpdate(child.getTableName(),
                column(parent.link()), "record_writer_parent_pid", conditions);
        if (rows != null) for (Map<String,Object> row : rows) {
            Object pid = row.get("record_writer_parent_pid");
            if (pid != null) checkParent(mapper, parent, pid);
        }
    }

    /** Same-value round trips are allowed; ownership never transfers after creation. */
    public static void assertMarkerUnchanged(ModelDefinition model, Map<String,Object> input, Map<String,Object> stored) {
        Policy policy = policy(model);
        if (policy == null || input == null) return;
        assertMatchingAliases(policy, input);
        for (String name : new java.util.LinkedHashSet<>(List.of(policy.marker().getCode(), column(policy.marker())))) {
            if (!input.containsKey(name)) continue;
            Object current = stored == null ? null : stored.containsKey(policy.marker().getCode())
                ? stored.get(policy.marker().getCode()) : stored.get(column(policy.marker()));
            if (!java.util.Objects.equals(current, input.get(name)))
                throw new MetaServiceException("RECORD_OWNERSHIP_IMMUTABLE: record writer marker cannot change after creation");
        }
    }

    /** SQL writers must also preserve the selector when no pre-read is available. */
    public static void appendMarkerInvariant(StringBuilder sql, ModelDefinition model, Map<String,Object> input) {
        Policy policy = policy(model);
        if (policy == null || input == null) return;
        assertMatchingAliases(policy, input);
        for (String name : new java.util.LinkedHashSet<>(List.of(policy.marker().getCode(), column(policy.marker())))) {
            if (!input.containsKey(name)) continue;
            Object proposed = input.get(name);
            if (proposed != null && !(proposed instanceof Boolean))
                throw new MetaServiceException("Record writer marker must be a boolean");
            sql.append(" AND ").append(column(policy.marker())).append(proposed == null ? " IS NULL"
                : Boolean.TRUE.equals(proposed) ? " IS TRUE" : " IS FALSE");
            return;
        }
    }

    /** Legacy raw mapper paths cannot materialize the immutable selector. */
    public static void assertLegacyMarkerUntouched(ModelDefinition model, Map<String,Object> data) {
        Policy policy = policy(model);
        if (policy != null && data != null
                && (data.containsKey(policy.marker().getCode()) || data.containsKey(column(policy.marker()))))
            throw new MetaServiceException("RECORD_OWNERSHIP_IMMUTABLE: legacy writes cannot materialize the record writer marker");
    }

    private static void assertMatchingAliases(Policy policy, Map<String,Object> input) {
        String code = policy.marker().getCode();
        String physical = column(policy.marker());
        if (input.containsKey(code) && input.containsKey(physical) && !java.util.Objects.equals(input.get(code), input.get(physical)))
            throw new MetaServiceException("Conflicting record writer marker aliases");
    }

    public static void validatePolicy(ModelDefinition model) { policy(model); }

    /** Legacy mapper mutations keep their existing mapping but lock and validate their exact targets. */
    public static Map<String,Object> guardLegacyConditions(
            com.auraboot.framework.meta.mapper.DynamicDataMapper mapper, ModelDefinition model,
            String table, Map<String,Object> conditions, String operation) {
        return guardLegacyConditions(mapper, model, table, conditions, operation, null);
    }

    public static Map<String,Object> guardLegacyConditions(
            com.auraboot.framework.meta.mapper.DynamicDataMapper mapper, ModelDefinition model,
            String table, Map<String,Object> conditions, String operation, Map<String,Object> values) {
        return guardLegacyConditions(mapper, model, table, conditions, operation, values, null);
    }

    public static Map<String,Object> guardLegacyConditions(
            com.auraboot.framework.meta.mapper.DynamicDataMapper mapper, ModelDefinition model,
            String table, Map<String,Object> conditions, String operation, Map<String,Object> values,
            java.util.function.Function<String, ModelDefinition> resolver) {
        assertLegacyMarkerUntouched(model, values);
        guardParentInput(mapper, model, resolver, values);
        Policy policy = policy(model);
        if (policy == null) return conditions;
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new MetaServiceException("Record writer target checks require an active transaction");
        Long tenant = MetaContext.getCurrentTenantId();
        if (tenant == null) throw new MetaServiceException("Record writer target checks require tenant context");
        Map<String,Object> scoped = new java.util.LinkedHashMap<>(conditions);
        Object declaredTenant = scoped.putIfAbsent("tenant_id", tenant);
        if (declaredTenant != null && !tenant.toString().equals(declaredTenant.toString())) throw denied(model);
        scoped.put("tenant_id", tenant);
        guardStoredParents(mapper, model, resolver, scoped);
        List<Map<String,Object>> rows = mapper.selectRecordWriterTargetsForUpdate(
                table, column(policy.marker()), policy.marker().getCode(), scoped);
        if (scoped.containsKey("id") || scoped.containsKey("pid")) {
            if (rows == null || rows.size() != 1)
                throw new MetaServiceException("Record writer target is missing or ambiguous in the current tenant");
            if (scoped.containsKey("id")) {
                Object resolvedId = rows.get(0).get("id");
                if (resolvedId == null) throw new MetaServiceException("Record writer target has no stored identity");
                scoped.put("id", resolvedId);
            }
        }
        if(rows!=null) for(Map<String,Object> row:rows) assertStoredAllowed(model,row,operation);
        return scoped;
    }

    /** Junction changes must resolve every target in the current tenant before producing effects. */
    public static void guardRelationTargets(
            com.auraboot.framework.meta.mapper.DynamicDataMapper mapper, ModelDefinition model,
            String table, java.util.Collection<String> identities, String operation) {
        guardRelationTargets(mapper, model, table, identities, operation, null);
    }

    public static void guardRelationTargets(
            com.auraboot.framework.meta.mapper.DynamicDataMapper mapper, ModelDefinition model,
            String table, java.util.Collection<String> identities, String operation,
            java.util.function.Function<String, ModelDefinition> resolver) {
        Parent parent = parent(model, resolver);
        Policy policy = policy(model);
        if (policy == null) return;
        Long tenant = relationTenant();
        if (identities == null || identities.stream().anyMatch(identity -> identity == null || identity.isBlank()))
            throw new MetaServiceException("Record writer relation target is missing");
        for (String identity : new java.util.TreeSet<>(identities)) {
            Map<String,Object> conditions = new java.util.LinkedHashMap<>();
            conditions.put("tenant_id", tenant);
            conditions.put("id", identity);
            if (model.isSoftDelete()) conditions.put("deleted_flag", false);
            List<Map<String,Object>> rows = mapper.selectRecordWriterTargetsForUpdate(table, column(policy.marker()),
                    policy.marker().getCode(), conditions);
            if (rows == null || rows.size() != 1)
                throw new MetaServiceException("Record writer relation target is missing or ambiguous in the current tenant");
            assertStoredAllowed(model, rows.get(0), operation);
            if (parent != null) guardStoredParents(mapper, model, resolver,
                    Map.of("tenant_id", tenant, "pid", rows.get(0).get("pid")));
        }
    }

    /** Lock existing junction rows, then validate their targets before a replacement delete. */
    public static void guardRelationReplacement(
            com.auraboot.framework.meta.mapper.DynamicDataMapper mapper, ModelDefinition model,
            String targetTable, String junctionTable, String sourceColumn, String targetColumn, String sourceIdentity) {
        guardRelationReplacement(mapper, model, targetTable, junctionTable, sourceColumn, targetColumn, sourceIdentity, null);
    }

    public static void guardRelationReplacement(
            com.auraboot.framework.meta.mapper.DynamicDataMapper mapper, ModelDefinition model,
            String targetTable, String junctionTable, String sourceColumn, String targetColumn, String sourceIdentity,
            java.util.function.Function<String, ModelDefinition> resolver) {
        parent(model, resolver);
        if (policy(model) == null) return;
        Long tenant = relationTenant();
        List<Map<String,Object>> links = mapper.selectRelationTargetsForUpdate(
                junctionTable, sourceColumn, targetColumn, tenant, sourceIdentity);
        java.util.List<String> identities = new java.util.ArrayList<>();
        if (links != null) for (Map<String,Object> link : links) {
            Object identity = link.get("target_identity");
            if (identity == null) throw new MetaServiceException("Record writer relation target is missing");
            identities.add(identity.toString());
        }
        guardRelationTargets(mapper, model, targetTable, identities, "update", resolver);
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
