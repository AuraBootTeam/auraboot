package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.QueryBuilderService;
import com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/** Authorizes ordinary record field writes against the locked, unprojected physical row. */
@Service
@RequiredArgsConstructor
public class NativeRecordFieldAuthorization {
    private final MetaModelService metadata;
    private final QueryBuilderService queries;
    private final DynamicDataMapper rows;
    private final BusinessCommandRecordAuthorization authorization;

    public void requireUpdate(String modelCode, String recordId, Map<String,Object> submitted) {
        require("update", modelCode, recordId, submitted);
    }

    /** Called after insertion, before materialization or observation, in the inserting transaction. */
    public void requireCreated(String modelCode, String recordId, Map<String,Object> submitted) {
        require("create", modelCode, recordId, submitted);
    }

    private void require(String action, String modelCode, String recordId, Map<String,Object> submitted) {
        if (!MetaContext.exists() || MetaContext.getCurrentMemberId() == null
                || com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || MetaContext.hasCommandPermitScopeFor(modelCode)) return;
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || isolation == null || !(isolation == TransactionDefinition.ISOLATION_REPEATABLE_READ
                || isolation == TransactionDefinition.ISOLATION_SERIALIZABLE))
            throw denied("A coherent write transaction is required");
        var model = metadata.getModelDefinition(modelCode).orElseThrow(() -> denied("Model unavailable"));
        var primary = metadata.getPrimaryKeyField(modelCode);
        var conditions = new ArrayList<QueryCondition>();
        conditions.add(QueryCondition.builder().fieldName(primary.getCode()).operator(QueryCondition.Operator.EQ).value(recordId).build());
        conditions.add(QueryCondition.builder().fieldName("tenant_id").operator(QueryCondition.Operator.EQ).value(MetaContext.getCurrentTenantId()).build());
        if (model.isSoftDelete()) conditions.add(QueryCondition.builder().fieldName("deleted_flag").operator(QueryCondition.Operator.EQ).value(false).build());
        var lockConditions = new LinkedHashMap<String,Object>();
        lockConditions.put("tenant_id", MetaContext.getCurrentTenantId());
        lockConditions.put(primary.getColumnName() != null ? primary.getColumnName() : primary.getCode(), recordId);
        if (model.isSoftDelete()) lockConditions.put("deleted_flag", false);
        var locked = rows.selectRecordWriterTargetsForUpdate(model.getTableName(), "pid", "native_target_pid", lockConditions);
        if (locked == null || locked.size() != 1) throw denied("Target record unavailable");
        var query = queries.buildConditionQuery(model, conditions);
        query.includeNativeReadAuthorization();
        var records = rows.selectByQuery(query.getSql(), query.getParameterMap());
        if (records == null || records.size() != 1) throw denied("Target record unavailable");
        var raw = records.getFirst();
        if (!(raw.get("__ab_auth_pid") instanceof String pid) || pid.isBlank()
                || raw.get("__ab_auth_tenant") == null || !raw.containsKey("__ab_auth_creator"))
            throw denied("Canonical target facts unavailable");
        if (!Objects.equals(pid, locked.getFirst().get("pid"))) throw denied("Locked target identity changed");
        var canonical = new LinkedHashMap<String,Object>(raw);
        canonical.keySet().removeIf(key -> key.startsWith("__ab_auth_"));
        for (var field : model.getFields()) {
            // JSONB virtual selections already use the logical field code. Do not replace an
            // extracted value with the containing JSON object or a display/masked value.
            if (!canonical.containsKey(field.getCode()) && field.getColumnName() != null
                    && raw.containsKey(field.getColumnName()) && !field.isJsonbVirtual())
                canonical.put(field.getCode(), raw.get(field.getColumnName()));
        }
        canonical.put("pid", pid);
        canonical.put("tenant_id", raw.get("__ab_auth_tenant"));
        canonical.put("created_by", raw.get("__ab_auth_creator"));
        if (model.isSoftDelete()) canonical.put("deleted_flag", false);
        com.auraboot.framework.meta.util.JsonbFieldHelper.normalizeJsonReadValues(model, canonical);
        if ("create".equals(action) && !Objects.equals(canonical.get("created_by"), MetaContext.getCurrentUserId()))
            throw denied("Created record ownership is not the current actor");
        var grant = "create".equals(action)
                ? authorization.resolveOrdinaryCreate(MetaContext.getCurrentMemberId(), modelCode, canonical)
                : authorization.resolveOrdinaryUpdate(MetaContext.getCurrentMemberId(), modelCode, canonical);
        if (grant == null || !grant.result().granted()
                || !Objects.equals(grant.tenantId(), MetaContext.getCurrentTenantId())
                || !Objects.equals(grant.userId(), MetaContext.getCurrentUserId())
                || !Objects.equals(grant.memberId(), MetaContext.getCurrentMemberId())
                || !Objects.equals(grant.modelCode(), modelCode) || !Objects.equals(grant.recordPid(), pid))
            throw denied("The complete write grant does not match this record");
        for (String key : submitted.keySet()) {
            if ("update".equals(action) && "_expectedVersion".equals(key)) continue;
            if (com.auraboot.framework.meta.constant.SystemFieldConstants.isSystemField(key)) {
                if (("pid".equals(key) || "update".equals(action) && "row_version".equals(key)) && canonical.containsKey(key)
                        && !ValidationServiceImpl.valueChanges(canonical.get(key), submitted.get(key))) continue;
                throw denied("Immutable submitted field");
            }
            FieldDefinition field = model.getFields().stream().filter(f -> key.equals(f.getCode())).findFirst().orElse(null);
            if (field == null) {
                var aliases = model.getFields().stream().filter(f -> !f.isJsonbVirtual() && key.equals(f.getColumnName())).toList();
                if (aliases.size() == 1) field = aliases.getFirst();
            }
            if (field == null) throw denied("Unknown submitted field");
            if (com.auraboot.framework.meta.constant.SystemFieldConstants.isSystemField(field.getCode())
                    || com.auraboot.framework.meta.constant.SystemFieldConstants.isSystemField(field.getColumnName()))
                throw denied("Immutable submitted field alias");
            if (!grant.canWrite(field.getCode())) throw denied("Submitted field is outside the matching write grant");
        }
    }

    private static AccessDeniedException denied(String reason) {
        return new AccessDeniedException("FIELD_WRITE_DENIED: " + reason);
    }
}
