package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Freezes an aggregate after its owner stores a non-null external-operation claim. */
public final class RecordMutationLockGuard {
    public static final String KEY = "recordMutationLock";
    private RecordMutationLockGuard() {}
    private record Policy(FieldDefinition claim, Map<?, ?> commands) {}
    private record Parent(ModelDefinition model, FieldDefinition link, Policy policy) {}


    private static MetaServiceException invalid() {
        return new MetaServiceException("Invalid recordMutationLock declaration");
    }
    private static Policy policy(ModelDefinition model) {
        Object raw = model == null || model.getExtension() == null ? null : model.getExtension().get(KEY);
        if (raw == null) return null;
        com.auraboot.framework.meta.security.RecordMutationLockDeclaration.validate(raw);
        Map<?, ?> value = (Map<?, ?>) raw;
        if (model.getFields() == null) throw invalid();
        FieldDefinition field = model.getFields().stream().filter(f -> f != null && value.get("field").equals(f.getCode()))
                .findFirst().orElseThrow(RecordMutationLockGuard::invalid);
        if (field.isVirtual() || field.isJsonbVirtual()
                || !("reference".equals(field.getDataType()) || "string".equals(field.getDataType()))) throw invalid();
        column(field);
        return new Policy(field, (Map<?, ?>) value.get("commands"));
    }
    public static void validatePolicy(ModelDefinition model) {
        policy(model);
        Object inherit = model == null || model.getExtension() == null ? null : model.getExtension().get("inheritParentMutationLock");
        if (inherit != null && !Boolean.TRUE.equals(inherit)) throw invalid();
    }
    private static String column(FieldDefinition field) {
        return SqlSafetyUtils.requireIdentifier(field.getColumnName() == null || field.getColumnName().isBlank()
                ? field.getCode() : field.getColumnName(), "mutation lock column");
    }
    private static boolean allowed(Policy policy, String operation) {
        if (!Set.of("create", "update", "delete").contains(operation)) throw invalid();
        return MetaContext.hasCommandPermitScope() && MetaContext.getAuthorizedCommandCode() != null
                && ((List<?>) policy.commands().get(operation)).contains(MetaContext.getAuthorizedCommandCode());
    }
    private static MetaServiceException denied() {
        return new MetaServiceException("RECORD_MUTATION_LOCKED: aggregate already owns a durable operation claim");
    }
    private static Long tenant() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || MetaContext.getCurrentTenantId() == null)
            throw new MetaServiceException("Mutation lock requires an active tenant transaction");
        return MetaContext.getCurrentTenantId();
    }
    private static Parent parent(ModelDefinition child, Function<String, ModelDefinition> resolver) {
        Object inherit = child == null || child.getExtension() == null ? null : child.getExtension().get("inheritParentMutationLock");
        if (inherit == null) return null;
        if (!Boolean.TRUE.equals(inherit) || resolver == null) throw invalid();
        Object code = child.getExtension().get("parentModel");
        if (!(code instanceof String name) || name.isBlank()) throw invalid();
        ModelDefinition root = resolver.apply(name);
        if (root == null) throw invalid();
        Policy policy = policy(root);
        if (policy == null) throw invalid();
        Object linkCode = child.getExtension().get("parentField");
        if (child.getFields() == null) throw invalid();
        FieldDefinition link = child.getFields().stream().filter(f -> f != null && f.getCode().equals(linkCode))
                .findFirst().orElseThrow(RecordMutationLockGuard::invalid);
        if (link.isVirtual() || link.isJsonbVirtual() || link.getRefTarget() == null
                || !name.equals(link.getRefTarget().getTargetEntity())
                || !"pid".equals(link.getRefTarget().getValueField())) throw invalid();
        return new Parent(root, link, policy);
    }
    /** Claim acquisition itself is owner-command-only, including on previously unclaimed rows. */
    public static void assertInputAllowed(ModelDefinition model, Map<String, Object> input, String operation) {
        Policy policy = policy(model);
        if (policy == null || input == null) return;
        String code = policy.claim().getCode(), physical = column(policy.claim());
        if (input.containsKey(code) && input.containsKey(physical)
                && !java.util.Objects.equals(input.get(code), input.get(physical))) throw invalid();
        if ((input.get(code) != null || input.get(physical) != null) && !allowed(policy, operation)) throw denied();
    }
    private static List<Map<String, Object>> locked(DynamicDataMapper mapper, ModelDefinition model,
            FieldDefinition field, Map<String, Object> conditions) {
        Map<String, Object> scoped = new LinkedHashMap<>(conditions);
        Long tenant = tenant();
        Object supplied = scoped.put("tenant_id", tenant);
        if (supplied != null && !tenant.toString().equals(supplied.toString())) throw denied();
        if (model.isSoftDelete()) scoped.put("deleted_flag", false);
        return mapper.selectRecordWriterTargetsForUpdate(
                SqlSafetyUtils.requireIdentifier(model.getTableName(), "mutation lock table"), column(field), "mutation_lock_value", scoped);
    }
    private static void guardParent(DynamicDataMapper mapper, Parent parent, Object pid) {
        if (!(pid instanceof String identity) || identity.isBlank()) throw invalid();
        List<Map<String, Object>> rows = locked(mapper, parent.model(), parent.policy().claim(), Map.of("pid", identity));
        if (rows == null || rows.size() != 1) throw denied();
        if (rows.get(0).get("mutation_lock_value") != null) throw denied();
    }
    public static void guardParentInput(DynamicDataMapper mapper, ModelDefinition model,
            Function<String, ModelDefinition> resolver, Map<String, Object> input) {
        Parent parent = parent(model, resolver);
        if (parent == null || input == null) return;
        String code = parent.link().getCode(), physical = column(parent.link());
        if (input.containsKey(code) && input.containsKey(physical)
                && !java.util.Objects.equals(input.get(code), input.get(physical))) throw invalid();
        Object pid = input.containsKey(code) ? input.get(code) : input.get(physical);
        if (input.containsKey(code) || input.containsKey(physical)) guardParent(mapper, parent, pid);
    }
    /** Called by every existing-target writer before SQL, including raw field-map/side effects. */
    public static void guardTargets(DynamicDataMapper mapper, ModelDefinition model,
            Function<String, ModelDefinition> resolver, String operation, Map<String, Object> conditions,
            Map<String, Object> values) {
        Policy own = policy(model);
        Parent parent = parent(model, resolver);
        if (own == null && parent == null) return;
        assertInputAllowed(model, values, operation);
        guardParentInput(mapper, model, resolver, values);
        FieldDefinition selected = own != null ? own.claim() : parent.link();
        List<Map<String, Object>> rows = locked(mapper, model, selected, conditions);
        if (rows == null || ((conditions.containsKey("id") || conditions.containsKey("pid")) && rows.size() != 1)) throw denied();
        for (Map<String, Object> row : rows) {
            Object value = row.get("mutation_lock_value");
            if (own != null && value != null && !allowed(own, operation)) throw denied();

        }
        if (parent != null) {
            List<Map<String, Object>> children = own == null ? rows : locked(mapper, model, parent.link(), conditions);
            for (Map<String, Object> child : children) guardParent(mapper, parent, child.get("mutation_lock_value"));
        }
    }
    public static void validateRelations(ModelDefinition model, Function<String, ModelDefinition> resolver) {
        validatePolicy(model);
        parent(model, resolver);
    }
    public static Map<String, Object> targetConditions(String column, Object identity, Long tenant) {
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put(column, identity);
        conditions.put("tenant_id", tenant);
        return conditions;
    }
    public static boolean hasPolicy(ModelDefinition model) {
        return policy(model) != null || (model != null && model.getExtension() != null
                && model.getExtension().containsKey("inheritParentMutationLock"));
    }
    public static void guardRelationTargets(DynamicDataMapper mapper, ModelDefinition model,
            Function<String, ModelDefinition> resolver, java.util.Collection<String> identities, String operation) {
        if (!hasPolicy(model)) return;
        if (identities == null || identities.stream().anyMatch(v -> v == null || v.isBlank())) throw invalid();
        for (String identity : new java.util.TreeSet<>(identities))
            guardTargets(mapper, model, resolver, operation, Map.of("id", identity), Map.of());
    }
}
