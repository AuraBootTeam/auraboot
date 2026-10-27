package com.auraboot.framework.file.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.DataAccessAuthorizationHelper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.FieldWriterGuard;
import com.auraboot.framework.meta.service.impl.ModelMutationGuard;
import com.auraboot.framework.meta.service.impl.RecordCommandWriterGuard;
import com.auraboot.framework.meta.service.impl.RecordMutationLockGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Objects;

/** The same metadata invariants apply to legacy relation writes and to native field writes. */
@Component
@RequiredArgsConstructor
public class FileRelationMutationGuard {
    private final ApplicationContext beans;
    private final DynamicDataMapper records;

    public void authorize(String modelCode, String pid, String fieldName) {
        if (!MetaContext.exists() || MetaContext.isImpersonating() || MetaContext.getCurrentTenantId() == null || MetaContext.getCurrentUserId() == null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || modelCode == null || pid == null || fieldName == null || fieldName.isBlank()) throw denied();
        MetaModelService models = beans.getBean(MetaModelService.class);
        ModelDefinition model = models.getModelDefinition(modelCode).orElseThrow(FileRelationMutationGuard::denied);
        if (!"published".equalsIgnoreCase(model.getStatus()) || model.getFields() == null) throw denied();
        // Legacy exports use opaque attachment-slot names. They still obey model/record policies;
        // an actual file field (including its physical column alias) always obeys its field policy.
        FieldDefinition field = model.getFields().stream().filter(Objects::nonNull)
                .filter(f -> fieldName.equals(f.getCode()) || fieldName.equals(f.getColumnName()))
                .findFirst().orElse(null);
        if (field != null && !"file".equals(field.getDataType())) throw denied();
        String original = MetaContext.getCommandPermitTargetFor(modelCode);
        if (original != null && !original.equals(pid)) throw denied();
        Map<String,Object> conditions = Map.of("tenant_id", MetaContext.getCurrentTenantId(), "pid", pid);
        var rows = records.selectRecordWriterTargetsForUpdate(
                SqlSafetyUtils.requireIdentifier(model.getTableName(), "file relation target table"), "pid", "file_target", conditions);
        if (rows == null || rows.size() != 1) throw denied();
        Map<String,Object> parent = beans.getBean(DynamicDataService.class).getById(modelCode,pid);
        if (parent == null || !pid.equals(parent.get("pid")) || Boolean.TRUE.equals(parent.get("deleted_flag"))
                || !(parent.get("tenant_id") instanceof Number tenant)
                || tenant.longValue() != MetaContext.getCurrentTenantId()) throw denied();
        if (!beans.getBean(DataAccessAuthorizationHelper.class).authorizeRecord(modelCode,"update",parent)) throw denied();
        Long expected = MetaContext.getCommandExpectedVersion(modelCode,pid);
        if (expected != null && (!(parent.get("row_version") instanceof Number version) || version.longValue() != expected))
            throw new com.auraboot.framework.exception.ConflictException("iot.error.version_conflict");
        assertWritableMetadata(model,field,parent);
        RecordMutationLockGuard.guardTargets(records,model,code -> models.getModelDefinition(code).orElse(null),
                "update",conditions,Map.of());
    }

    /** Shared by replacement, removal, legacy append and the target-bound append capability. */
    public static void assertWritableMetadata(ModelDefinition model, FieldDefinition field, Map<String,Object> parent) {
        ModelMutationGuard.assertMutable(model,"update");
        RecordCommandWriterGuard.assertStoredAllowed(model,parent,"update");
        if (field == null) return;
        FieldWriterGuard.assertFieldWriteAllowed(model,field.getCode());
        if (field.isImmutable() || (field.getImmutableWhen() != null && field.getImmutableWhen().getIn() != null
                && field.getImmutableWhen().getIn().contains(Objects.toString(parent.get(field.getImmutableWhen().getField()),null))))
            throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("File relation mutation requires an authorized mutable business target and field");
    }
}
