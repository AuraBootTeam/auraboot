package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Internal physical-DDL policy; logical binding.required is never modified. */
@Component
@RequiredArgsConstructor
public class ModelFieldRenameColumnPolicy {
    private final JdbcTemplate jdbc;
    private final ThreadLocal<List<ApprovedField>> pending = new ThreadLocal<>();

    public record ApprovedField(long tenantId, String pluginPid, String modelCode, String fieldCode) {}

    public AutoCloseableScope open(List<ApprovedField> fields) {
        if (pending.get() != null) throw new IllegalStateException("Nested field migration scope");
        pending.set(List.copyOf(fields));
        return () -> pending.remove();
    }

    @FunctionalInterface
    public interface AutoCloseableScope extends AutoCloseable {
        @Override void close();
    }

    public boolean deferNotNull(String table, String column) {
        // A shared physical table cannot become globally NOT NULL while other tenants
        // still use the retained legacy column. The durable ledger owns this exception.
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM ab_plugin_field_rename
                 WHERE physical_table=? AND (to_field=? OR from_field=?) AND state='applied')
                """, Boolean.class, table, column, column))) return true;
        List<ApprovedField> fields = pending.get();
        if (fields == null) return false;
        Long tenant = MetaContext.getCurrentTenantId();
        for (ApprovedField field : fields) {
            if (!Long.valueOf(field.tenantId()).equals(tenant) || !field.fieldCode().equals(column)) continue;
            // Resolve from this tenant's real model/binding after resource import, never
            // from another tenant's table name or a mapping-file physical identifier.
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM ab_meta_model m
                    JOIN ab_meta_model_field_binding b ON b.model_id=m.id AND b.tenant_id=m.tenant_id
                    JOIN ab_meta_field f ON f.id=b.field_id
                    WHERE m.tenant_id=? AND m.plugin_pid=? AND m.code=? AND coalesce(nullif(m.table_name,''),?)=?
                      AND m.is_current=true AND m.deleted_flag=false AND b.deleted_flag=false
                      AND f.code=? AND f.deleted_flag=false)
                    """, Boolean.class, tenant, field.pluginPid(), field.modelCode(), com.auraboot.framework.meta.constant.SystemFieldConstants.generateTableName(field.modelCode()), table, column))) return true;
        }
        return false;
    }
}
