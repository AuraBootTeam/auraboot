package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.ExportTask;
import com.auraboot.framework.meta.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Path;
import java.util.Objects;

/** Queue row is always locked first; the caller performs all file I/O outside these transactions. */
@Service
@RequiredArgsConstructor
public class DomainExportPersistence {
    private final AsyncTaskMapper tasks;
    private final DomainExportMapper exports;
    @Transactional
    public void begin(ExportTask task,String attempt,Path temporary,String key) {
        var execution=AsyncTaskExecutionContext.require();
        lock(execution,task);
        if(exports.running(execution.tenantId(),execution.createdBy(),task.getPid())!=1)
            throw new CannotAcquireLockException("Domain export is no longer writable");
        if(exports.attempt(attempt,execution.tenantId(),task.getPid(),execution.token(),key,temporary.toString(),task.getExpiresAt())!=1)
            throw new IllegalStateException("Domain export attempt was not registered");
    }
    @Transactional
    public void complete(ExportTask task,ExportArtifactStore.Stored artifact) {
        var execution=AsyncTaskExecutionContext.require();lock(execution,task);
        if(exports.complete(execution.tenantId(),execution.createdBy(),task.getPid(),artifact.key(),artifact.size(),artifact.checksum())!=1)
            throw new CannotAcquireLockException("Domain export completion lost its ownership fence");
    }
    private void lock(AsyncTaskExecutionContext.Snapshot execution,ExportTask task) {
        if(MetaContext.runWithoutTenantFilter(()->tasks.lockOwnedExecution(execution.taskId(),execution.tenantId(),execution.token()))==null)
            throw new CannotAcquireLockException("Domain export execution lease is no longer live");
        var current=exports.lock(execution.tenantId(),execution.createdBy(),task.getPid());
        if(current==null || !Objects.equals(current.getQueryCode(),task.getQueryCode())
            || !Objects.equals(current.getRequestParams(),task.getRequestParams())
            || !Objects.equals(current.getRequestParams().path("asyncTaskCode").textValue(),execution.taskCode()))
            throw new IllegalStateException("Domain export execution binding changed");
    }
}
