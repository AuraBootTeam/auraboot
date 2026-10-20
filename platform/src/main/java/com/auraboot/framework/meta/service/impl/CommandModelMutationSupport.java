package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ModelMutationObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Bridges command-owned SQL writes to the same synchronous model observers. */
@Component
@RequiredArgsConstructor
public class CommandModelMutationSupport {
    private final MetaModelService models;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<ModelMutationObserver> observers;

    public void observe(String modelCode, Map.Entry<String, Object> identifier, Long tenantId,
                        ModelMutationObserver.Operation operation) {
        var model = models.getModelDefinition(modelCode).orElse(null);
        // Legacy table-only commands have no published model declaration.
        if (model == null) return;
        if (!Objects.equals(tenantId, MetaContext.getCurrentTenantId())) {
            throw new IllegalStateException("Command event tenant does not match the owner context");
        }
        String table = SqlSafetyUtils.requireIdentifier(model.getTableName(), "command event table");
        String idColumn = identifier.getKey();
        if (!"id".equals(idColumn) && !"pid".equals(idColumn)) {
            throw new IllegalArgumentException("Command event requires a record identity");
        }
        // Numeric legacy command identities are resolved inside the owner transaction;
        // the public ordering key always uses the source PID. Locks also protect deletes.
        var pids = jdbc.queryForList("SELECT pid FROM " + table
                + " WHERE tenant_id = ? AND " + idColumn + " = ? FOR UPDATE",
                String.class, tenantId, identifier.getValue());
        if (pids.isEmpty()) return;
        if (pids.size() != 1) throw new IllegalStateException("Command event identity is ambiguous");
        observers.orderedStream().forEach(observer -> observer.observe(model, pids.getFirst(), operation));
    }
}
