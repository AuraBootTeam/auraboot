package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.dao.mapper.FileMapper;
import com.auraboot.framework.file.dao.mapper.FileRelationMapper;
import com.auraboot.framework.file.entity.FileRelationEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.RecordMutationLockGuard;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.FileAccessor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Installs only the human root command's transaction-owned attachment write capability. */
@Component
@RequiredArgsConstructor
public class CommandTargetFileAccessorFactory {
    private final FileService files;
    private final StorageProvider storage;
    private final FileMapper fileMapper;
    private final FileRelationMapper relations;
    private final SqlSessionFactory sessions;
    private final MetaModelService models;
    private final DynamicDataService data;
    private final DynamicDataMapper records;

    private static final ThreadLocal<Scope> ACTIVE_HANDLER = new ThreadLocal<>();

    private record Actor(Long tenant, Long user, Long member, String userPid) {}

    public static final class Scope implements AutoCloseable {
        private volatile boolean active = true;
        private final Thread thread = Thread.currentThread();
        private FileAccessor accessor;
        private final java.util.SortedSet<String> lockedFiles = new java.util.TreeSet<>();
        public FileAccessor accessor() { return accessor; }
        @Override public void close() {
            active = false;
            if (ACTIVE_HANDLER.get() == this) ACTIVE_HANDLER.remove();
        }
    }

    /** Returns null when the pipeline cannot attest a synchronous authorized human target. */
    public Scope forCommand(CommandPipelineContext context) {
        if (ACTIVE_HANDLER.get() != null || context == null || context.getRequest() == null || context.getRequest().isDryRun()
                || context.getCommand() == null || context.getAuthorizationVerdict() == null
                || !context.getAuthorizationVerdict().isAuthorized() || context.getPermitPlan() == null
                || !context.getPermitPlan().isPermitted() || !Boolean.TRUE.equals(context.getTargetRecordReadable())
                || !MetaContext.exists() || MetaContext.isImpersonating() || !MetaContext.isRootAuthorizedCommand()
                || com.auraboot.framework.meta.service.impl.AsyncTaskExecutionContext.isActive()
                || com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || !writableTransaction()) return null;
        Actor actor = current();
        String model = context.getCommand().getModelCode();
        String command = context.getCommand().getCode();
        String target = MetaContext.getCommandPermitTargetFor(model);
        if (actor.tenant() == null || actor.user() == null || actor.member() == null
                || actor.tenant() <= 0 || actor.user() <= 0 || actor.member() <= 0
                || actor.userPid() == null || actor.userPid().isBlank()
                || !actor.tenant().equals(context.getTenantId()) || !actor.user().equals(context.getUserId())
                || target == null || target.isBlank() || !Objects.equals(command, MetaContext.getAuthorizedCommandCode()))
            return null;
        var source = sessions.getConfiguration().getEnvironment().getDataSource();
        Object resource = TransactionSynchronizationManager.getResource(source);
        if (!(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()) return null;
        Connection connection = holder.getConnection();
        try {
            if (connection.isClosed() || connection.isReadOnly() || connection.getAutoCommit()) return null;
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot validate the Core attachment transaction", error);
        }
        var lifetime = new TransactionSynchronization() {};
        TransactionSynchronizationManager.registerSynchronization(lifetime);
        Scope scope = new Scope();
        ACTIVE_HANDLER.set(scope);
        scope.accessor = new FileAccessorImpl(files, storage, actor.user()) {
            @Override public void appendAndRetainExistingToTarget(String filePid, String fieldCode) {
                try {
                    if (!scope.active || scope.thread != Thread.currentThread() || !writableTransaction()
                        || MetaContext.isImpersonating() || !MetaContext.isRootAuthorizedCommand()
                        || com.auraboot.framework.meta.service.impl.AsyncTaskExecutionContext.isActive()
                        || !actor.equals(current()) || !command.equals(MetaContext.getAuthorizedCommandCode())
                        || !target.equals(MetaContext.getCommandPermitTargetFor(model))
                        || !TransactionSynchronizationManager.getSynchronizations().contains(lifetime)
                        || TransactionSynchronizationManager.getResource(source) != holder
                        || holder.getConnection() != connection) throw denied();
                    append(scope, actor, model, target, filePid, fieldCode);
                } catch (RuntimeException failure) {
                    // Even a plugin that catches the exception cannot commit a partial parent,
                    // retention lock or relation after a rejected append in this transaction.
                    if (scope.active && scope.thread == Thread.currentThread()) holder.setRollbackOnly();
                    throw failure;
                }
            }
        };
        return scope;
    }

    private void append(Scope scope, Actor actor, String modelCode, String target, String filePid, String fieldCode) {
        exact(filePid, 64); exact(fieldCode, 128);
        ModelDefinition model = models.getModelDefinition(modelCode).orElseThrow(CommandTargetFileAccessorFactory::denied);
        if (!"PUBLISHED".equalsIgnoreCase(model.getStatus()) || model.getFields() == null
                || (model.getExtension() != null && model.getExtension().get("parentModel") != null)) throw denied();
        FieldDefinition field = model.getFields().stream().filter(Objects::nonNull)
                .filter(f -> fieldCode.equals(f.getCode())).findFirst().orElseThrow(CommandTargetFileAccessorFactory::denied);
        if (!"file".equals(field.getDataType()) || field.isVirtual() || field.isJsonbVirtual()
                || field.getExtraProps() == null || !Boolean.TRUE.equals(field.getExtraProps().get("multiple"))) throw denied();
        // Parent locks always precede file locks. The original command's scoped read rechecks
        // visibility while the root is locked; no arbitrary model/PID enters the public API.
        var conditions = Map.<String,Object>of("tenant_id", actor.tenant(), "pid", target);
        var rows = records.selectRecordWriterTargetsForUpdate(
                SqlSafetyUtils.requireIdentifier(model.getTableName(), "attachment target table"),
                "pid", "attachment_target", conditions);
        if (rows == null || rows.size() != 1) throw denied();
        Map<String,Object> parent = data.getById(modelCode, target);
        if (parent == null || !target.equals(parent.get("pid")) || Boolean.TRUE.equals(parent.get("deleted_flag"))
                || !(parent.get("tenant_id") instanceof Number tenant) || tenant.longValue() != actor.tenant()) throw denied();
        Long expectedVersion = MetaContext.getCommandExpectedVersion(modelCode, target);
        if (expectedVersion != null && (!(parent.get("row_version") instanceof Number version)
                || version.longValue() != expectedVersion)) {
            throw new com.auraboot.framework.exception.ConflictException("iot.error.version_conflict");
        }
        com.auraboot.framework.file.service.impl.FileRelationMutationGuard.assertWritableMetadata(model,field,parent);
        RecordMutationLockGuard.guardTargets(records, model, code -> models.getModelDefinition(code).orElse(null),
                "update", conditions, Map.of());
        // A command may append several uploads. Require a stable order across distinct files
        // so two different roots cannot deadlock while retaining overlapping upload sets.
        if (!scope.lockedFiles.contains(filePid) && !scope.lockedFiles.isEmpty()
                && scope.lockedFiles.last().compareTo(filePid) > 0)
            throw new IllegalArgumentException("Append distinct file PIDs in ascending order");
        var file = fileMapper.selectActiveByPidForUpdate(actor.tenant(), filePid);
        if (file == null || file.getId() == null || !filePid.equals(file.getPid())
                || !actor.user().equals(file.getCreatedBy())
                || Boolean.TRUE.equals(file.getDeletedFlag()) || !"success".equalsIgnoreCase(file.getStatus())) throw denied();
        scope.lockedFiles.add(filePid);
        QueryWrapper<FileRelationEntity> query = new QueryWrapper<>();
        query.eq("tenant_id", actor.tenant()).eq("entity_type", modelCode).eq("entity_id", target)
                .eq("field_name", fieldCode).eq("deleted_flag", false).orderByAsc("sort_order", "id");
        List<FileRelationEntity> existing = relations.selectList(query);
        boolean linked = existing.stream().anyMatch(r -> String.valueOf(file.getId()).equals(r.getFileId()));
        if (!linked && field.getExtraProps().get("maxCount") instanceof Number max && existing.size() >= max.intValue())
            throw new IllegalArgumentException("Attachment field maximum count exceeded");
        if (!files.lockRetention(filePid)) throw new IllegalStateException("Attachment retention was not persisted");
        if (linked) return;
        int order = existing.stream().map(FileRelationEntity::getSortOrder).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(-1) + 1;
        FileRelationEntity relation = new FileRelationEntity().setTenantId(actor.tenant())
                .setEntityType(modelCode).setEntityId(target).setFieldName(fieldCode)
                .setFileId(String.valueOf(file.getId())).setSortOrder(order).setDeletedFlag(false);
        if (relations.insert(relation) != 1) throw new IllegalStateException("Attachment relation was not persisted");
    }

    private static Actor current() {
        return new Actor(MetaContext.getCurrentTenantId(), MetaContext.getCurrentUserId(),
                MetaContext.getCurrentMemberId(), MetaContext.getCurrentUserPid());
    }
    private static boolean writableTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }
    private static void exact(String value, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > max
                || value.codePoints().anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT)
                || value.contains("/") || value.contains("\\")) throw new IllegalArgumentException("An exact public file PID and field code are required");
    }
    private static AccessDeniedException denied() {
        return new AccessDeniedException("An authorized original human command target and transaction are required for file append");
    }
}
