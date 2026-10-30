package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.ColumnHasDataException;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Fail-closed physical-column protection shared by every schema deletion caller. */
@Component
@RequiredArgsConstructor
public class FieldDeletionGuard {
    private final JdbcTemplate jdbc;

    // These are installed platform relations, not caller-supplied SQL identifiers.
    // Retained versions count as references: deleting a column must not break an in-flight definition.
    private static final List<ReferenceSource> SOURCES = List.of(
            new ReferenceSource("ab_resource_reference", "deleted_flag IS NOT TRUE"),
            new ReferenceSource("ab_page_schema", "deleted_flag IS NOT TRUE"),
            new ReferenceSource("ab_named_query", "status IS DISTINCT FROM 'archived'"),
            new ReferenceSource("ab_named_query_field", "true"),
            new ReferenceSource("ab_command_definition", "deleted_flag IS NOT TRUE"),
            new ReferenceSource("ab_binding_rule", "deleted_flag IS NOT TRUE"),
            new ReferenceSource("ab_decision_definition", "deleted_flag IS NOT TRUE"),
            new ReferenceSource("ab_drt_definition", "true"),
            new ReferenceSource("ab_drt_version", "true"),
            new ReferenceSource("ab_drt_policy_definition", "true"),
            new ReferenceSource("ab_drt_policy_version", "true"),
            new ReferenceSource("ab_drt_condition_fragment", "status IS DISTINCT FROM 'archived'"),
            new ReferenceSource("ab_automation", "deleted_flag IS NOT TRUE"),
            new ReferenceSource("ab_dashboard", "deleted_flag IS NOT TRUE"),
            new ReferenceSource("ab_report", "deleted_flag IS NOT TRUE"),
            new ReferenceSource("ab_report_template", "deleted_flag IS NOT TRUE"));

    private record ReferenceSource(String table, String active) {}

    public void requireSafeDrop(ModelDefinition model, FieldDefinition field) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Field deletion requires an active transaction");
        }
        Long tenant = MetaContext.getCurrentTenantId();
        if (tenant == null) throw new BusinessException("Field deletion requires a tenant");
        String table = SqlSafetyUtils.requireIdentifier(model.getTableName(), "field deletion table");
        String column = SqlSafetyUtils.requireIdentifier(field.getColumnName(), "field deletion column");
        String code = SqlSafetyUtils.requireIdentifier(field.getCode(), "field deletion code");

        // Serialize data writes through the probe and DROP. No tenant filter: DDL affects every row.
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        jdbc.execute("LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE");
        lockReferenceSources();
        Boolean releaseOwned = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM ab_meta_model m
                JOIN ab_tenant_application_binding b ON b.tenant_id=m.tenant_id
                WHERE coalesce(nullif(m.table_name,''),'mt_' || m.code)=?
                  AND m.deleted_flag IS NOT TRUE AND m.plugin_pid IS NOT NULL
                  AND b.status IN ('active','shadow'))
                """, Boolean.class, table);
        if (!Boolean.FALSE.equals(releaseOwned)) {
            throw new BusinessException("Bound application models require a reviewed release migration");
        }
        Boolean shared = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM ab_meta_model_field_binding b
                JOIN ab_meta_model m ON m.id=b.model_id
                JOIN ab_meta_field f ON f.id=b.field_id
                WHERE b.deleted_flag IS NOT TRUE AND m.deleted_flag IS NOT TRUE AND f.deleted_flag IS NOT TRUE
                  AND coalesce(nullif(m.table_name,''),'mt_' || m.code)=?
                  AND f.code=? AND (m.code<>? OR m.tenant_id<>?))
                """, Boolean.class, table, code, model.getCode(), tenant);
        if (!Boolean.FALSE.equals(shared)) {
            throw new BusinessException("Field column is used by another active model binding");
        }
        Boolean hasData = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table
                + " WHERE " + column + " IS NOT NULL)", Boolean.class);
        if (!Boolean.FALSE.equals(hasData)) {
            throw new ColumnHasDataException("Field column contains data; ordinary deletion is refused");
        }
        requireNoDefinitionReferences(code, column);
    }

    public void requireDefinitionDeletion(String fieldCode) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Field deletion requires an active transaction");
        }
        String code = SqlSafetyUtils.requireIdentifier(fieldCode, "field deletion code");
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        lockReferenceSources();
        requireNoDefinitionReferences(code);
    }

    private void lockReferenceSources() {
        // Serialize binding changes and declared-reference writes until this transaction ends.
        jdbc.execute("LOCK TABLE ab_meta_model_field_binding, ab_meta_model, ab_meta_field, ab_tenant_application_binding IN SHARE MODE");
        for (ReferenceSource source : SOURCES) {
            jdbc.execute("LOCK TABLE " + source.table() + " IN SHARE MODE");
        }
    }

    private void requireNoDefinitionReferences(String code, String... storageNames) {
        // Match complete identifier tokens, including field comparisons, expressions and SQL.
        // Conservative matches may require removing an obsolete declaration before deletion.
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        names.add(code);
        for (String name : storageNames) names.add(SqlSafetyUtils.requireIdentifier(name, "field storage name"));
        List<String> aliases = jdbc.queryForList("""
                SELECT DISTINCT b.alias_code FROM ab_meta_model_field_binding b
                JOIN ab_meta_field f ON f.id=b.field_id
                WHERE f.code=? AND b.deleted_flag IS NOT TRUE AND f.deleted_flag IS NOT TRUE
                  AND b.alias_code IS NOT NULL AND b.alias_code<>''
                """, String.class, code);
        for (String alias : aliases) {
            if (!SqlSafetyUtils.isValidIdentifier(alias)) {
                throw new BusinessException("Cannot verify a registered field alias");
            }
            names.add(alias);
        }
        String token = "(^|[^A-Za-z0-9_])(" + String.join("|", names) + ")([^A-Za-z0-9_]|$)";
        for (ReferenceSource source : SOURCES) {
            Boolean referenced = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + source.table()
                    + " r WHERE " + source.active() + " AND to_jsonb(r)::text ~* ?)", Boolean.class, token);
            if (!Boolean.FALSE.equals(referenced)) {
                // Do not disclose names or tenant identities from a shared physical schema.
                throw new BusinessException("Field is referenced by retained application definitions");
            }
        }
        Boolean computedReference = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM ab_meta_field f WHERE deleted_flag IS NOT TRUE
                AND code<>? AND to_jsonb(f)::text ~* ?)
                """, Boolean.class, code, token);
        if (!Boolean.FALSE.equals(computedReference)) {
            throw new BusinessException("Field is referenced by another field definition");
        }
    }
}
