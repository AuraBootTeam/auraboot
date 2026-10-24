package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.infrastructure.storage.StorageProperties;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.ExportTask;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.meta.service.*;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Shared private-artifact lifecycle backed by the existing durable queue and export registry. */
@Service
@Slf4j
@RequiredArgsConstructor
public class DomainExportTaskService {
    public static final String TASK_TYPE="domain-export";
    public static final Duration ARTIFACT_RETENTION=Duration.ofHours(24);
    private final ExportTaskMapper exports;
    private final DomainExportMapper registry;
    private final AsyncTaskMapper queue;
    private final AsyncTaskServiceImpl tasks;
    private final DomainExportPersistence persistence;
    private final ExportArtifactStore artifacts;
    private final ObjectProvider<DomainExportSource> sources;
    private final ObjectMapper json;
    private final StorageProperties storage;
    private final AuditTrailService audit;
    @Value("${aura.export.domain.private-object-storage-confirmed:false}") private boolean privateObjectStorageConfirmed;

    public static boolean isDomain(ExportTask task) {
        return task!=null && task.getQueryCode()!=null && task.getQueryCode().startsWith("domain:");
    }
    @Transactional
    public ExportTaskDTO submit(String sourceCode,String reference) {
        requirePrivateStorage();
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        if(tenant==null || actor==null) throw new AccessDeniedException("An authenticated export owner is required");
        var prepared=source(sourceCode).prepare(reference);
        var task=new ExportTask();task.setPid(UlidGenerator.generate());task.setTenantId(tenant);task.setCreatedBy(actor);
        task.setQueryCode("domain:"+sourceCode);task.setStatus(ExportTask.STATUS_PENDING);task.setProgress(0);
        task.setTotalRows(prepared.rowCount());task.setProcessedRows(0L);task.setFormat(prepared.format());
        task.setCreatedAt(Instant.now());task.setExpiresAt(task.getCreatedAt().plus(ARTIFACT_RETENTION));
        var metadata=json.createObjectNode().put("sourceType","domain").put("sourceCode",sourceCode).set("prepared",json.valueToTree(prepared));
        task.setRequestParams(metadata);
        if(exports.insert(task)!=1) throw new IllegalStateException("Domain export registry insertion failed");
        var request=new AsyncTaskSubmitRequest();request.setTaskType(TASK_TYPE);request.setTaskName(prepared.fileName());
        request.setMaxRetries(2);request.setTimeoutSeconds(3600);
        request.setInputParams(json.createObjectNode().put("exportTaskPid",task.getPid()).put("resumeOnRestart",true));
        var queued=tasks.submitTask(request,tenant,actor);
        ((com.fasterxml.jackson.databind.node.ObjectNode)metadata).put("asyncTaskCode",queued.getTaskCode());
        if(exports.updateById(task)!=1) throw new IllegalStateException("Domain export queue binding failed");
        return view(task);
    }

    public AsyncTaskResult execute(JsonNode input,AsyncTaskExecutor.ProgressCallback progress) throws IOException {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Domain export file generation must run outside a transaction");
        requirePrivateStorage();var execution=AsyncTaskExecutionContext.require();
        if(input==null || !input.isObject() || input.size()!=2 || !input.path("resumeOnRestart").asBoolean(false)
            || !input.path("exportTaskPid").isTextual()) throw new IllegalArgumentException("Invalid domain export queue input");
        var task=registry.owned(execution.tenantId(),execution.createdBy(),input.path("exportTaskPid").textValue());
        if(!isDomain(task) || !execution.taskCode().equals(task.getRequestParams().path("asyncTaskCode").textValue()))
            throw new AccessDeniedException("Domain export queue owner binding is unavailable");
        var prepared=prepared(task);var source=source(sourceCode(task));
        var owner=new DomainExportSource.Owner(task.getTenantId(),task.getCreatedBy(),prepared.memberPid());
        source.authorizeForOwner(prepared,owner);
        Path temporary=Files.createTempFile("domain-export-",".csv");
        String attempt=UlidGenerator.generate();
        String key="exports/"+task.getTenantId()+"/"+task.getCreatedBy()+"/"+task.getPid()+"/"+execution.token()+"/"+attempt+".csv";
        try {
            persistence.begin(task,attempt,temporary,key);
            var result=source.generate(prepared,owner,temporary,progress);
            if(!Boolean.TRUE.equals(result.getSuccess()) || !temporary.toString().equals(result.getFilePath())
                || !Objects.equals(result.getRecordCount(),prepared.rowCount()))
                throw new IllegalStateException("Domain export source violated its registered artifact contract");
            var stored=artifacts.upload(temporary,key);
            return AsyncTaskResult.ok(json.createObjectNode().put("exportTaskPid",task.getPid()))
                .withTransactionalCompletion(()->{
                    source.authorizeForOwner(prepared,owner);
                    persistence.complete(task,stored);
                });
        } finally {
            try {Files.deleteIfExists(temporary);}
            // Best-effort local cleanup: the durable attempt retains the exact path for later deletion.
            catch(IOException cleanupFailure) {log.warn("Private export temporary cleanup deferred: attempt={}",attempt,cleanupFailure);}
        }
    }

    public DomainExportTaskDTO status(String pid) {var task=owned(pid);authorize(task);return view(task);}
    public ExportTaskDTO baseStatus(String pid) {
        var view=new ExportTaskDTO();org.springframework.beans.BeanUtils.copyProperties(status(pid),view);return view;
    }
    public boolean visible(ExportTask task) {
        requireOwner(task);
        return task.getExpiresAt()!=null && Instant.now().isBefore(task.getExpiresAt()) && source(sourceCode(task)).visible(prepared(task));
    }
    public void authorize(ExportTask task) {requireOwner(task);source(sourceCode(task)).authorize(prepared(task));}

    public record Download(InputStream content,long size,String fileName) {}
    public Download download(String pid) throws IOException {
        var task=owned(pid);
        try {authorize(task);}
        catch(AccessDeniedException | BusinessException denied) {downloadAudit(task,"denied");throw denied;}
        if(!ExportTask.STATUS_COMPLETED.equals(task.getStatus()) || task.getExpiresAt()==null || !Instant.now().isBefore(task.getExpiresAt())) {
            downloadAudit(task,"unavailable");
            throw BusinessException.i18n("export.artifact_unavailable");
        }
        requirePrivateStorage();
        final ExportArtifactStore.Download artifact;
        try {artifact=artifacts.verified(task.getFileKey(),Objects.requireNonNull(task.getFileSize()),task.getRequestParams().path("checksumSha256").textValue());}
        catch(IOException | BusinessException rejected) {downloadAudit(task,"artifact_rejected");throw rejected;}
        try {downloadAudit(task,"authorized");}
        catch(RuntimeException failure) {try {artifact.content().close();} catch(IOException cleanupFailure) {failure.addSuppressed(cleanupFailure);}throw failure;}
        return new Download(artifact.content(),artifact.size(),prepared(task).fileName());
    }
    private void downloadAudit(ExportTask task,String outcome) {
        var prepared=prepared(task);
        var metadata=json.createObjectNode().put("outcome",outcome).put("reference",prepared.reference()).put("rows",prepared.rowCount())
            .set("source",source(sourceCode(task)).auditMetadata(prepared));
        // Audit means permission to start this download, not proof of bytes received by a client.
        audit.recordAudit(AuditTrailEvent.builder().tenantId(MetaContext.getCurrentTenantId()).actorId(MetaContext.getCurrentUserId())
            .entityPid(task.getPid()).entityType("export_task").eventType("domain_export_download").operationType("download")
            .metadata(metadata).build());
    }
    private ExportTask owned(String pid) {
        var task=registry.owned(MetaContext.getCurrentTenantId(),MetaContext.getCurrentUserId(),pid);requireOwner(task);
        if(!isDomain(task)) throw new AccessDeniedException("Domain export is unavailable");return task;
    }
    private void requireOwner(ExportTask task) {
        if(task==null || MetaContext.getCurrentTenantId()==null || MetaContext.getCurrentUserId()==null
            || !Objects.equals(task.getTenantId(),MetaContext.getCurrentTenantId()) || !Objects.equals(task.getCreatedBy(),MetaContext.getCurrentUserId()))
            throw new AccessDeniedException("Domain export is unavailable to the current owner");
    }
    private DomainExportSource source(String code) {
        var matches=sources.orderedStream().filter(source->source.code().equals(code)).toList();
        if(matches.size()!=1) throw new IllegalStateException("Domain export source must be uniquely registered: "+code);
        return matches.getFirst();
    }
    private String sourceCode(ExportTask task) {
        if(!isDomain(task) || task.getRequestParams()==null || !"domain".equals(task.getRequestParams().path("sourceType").textValue()))
            throw new IllegalStateException("Invalid domain export source metadata");
        String code=task.getRequestParams().required("sourceCode").textValue();
        if(!task.getQueryCode().equals("domain:"+code)) throw new IllegalStateException("Domain export source code binding changed");return code;
    }
    private DomainExportSource.Prepared prepared(ExportTask task) {
        return DomainExportSource.Prepared.fromJson(task.getRequestParams().required("prepared"));
    }
    private DomainExportTaskDTO view(ExportTask task) {
        var dto=new DomainExportTaskDTO();dto.setPid(task.getPid());dto.setQueryCode(task.getQueryCode());dto.setFormat(task.getFormat());
        var descriptor=prepared(task);dto.setFileName(descriptor.fileName());dto.setSourceSnapshot(source(sourceCode(task)).publicMetadata(descriptor));
        if(dto.getSourceSnapshot()==null || !dto.getSourceSnapshot().isObject()) throw new IllegalStateException("Domain export public source description must be an object");
        String title=dto.getSourceSnapshot().path("title").textValue();dto.setTitle(title==null || title.isBlank() ? descriptor.fileName() : title);
        dto.setStatus(task.getStatus());dto.setProgress(task.getProgress());dto.setTotalRows(task.getTotalRows());dto.setProcessedRows(task.getProcessedRows());
        dto.setFileSize(task.getFileSize());
        if(task.getExpiresAt()==null || !Instant.now().isBefore(task.getExpiresAt())) dto.setStatus(ExportTask.STATUS_EXPIRED);
        else if(!ExportTask.STATUS_COMPLETED.equals(task.getStatus())) {
            var queued=queue.findByTaskCode(task.getRequestParams().required("asyncTaskCode").textValue());
            if(queued==null || !Objects.equals(queued.getTenantId(),task.getTenantId()) || !Objects.equals(queued.getCreatedBy(),task.getCreatedBy()))
                throw new IllegalStateException("Domain export queue entry is unavailable");
            dto.setStatus(queued.getStatus());dto.setProgress(queued.getProgress());
            if("failed".equals(queued.getStatus())) dto.setErrorMessage("$i18n:export.domain_failed");
        }
        if(ExportTask.STATUS_COMPLETED.equals(dto.getStatus())) dto.setDownloadUrl("/api/exports/domain/"+task.getPid()+"/download");
        dto.setCreatedAt(local(task.getCreatedAt()));dto.setCompletedAt(local(task.getCompletedAt()));dto.setExpiresAt(local(task.getExpiresAt()));return dto;
    }
    private static LocalDateTime local(Instant value){return value==null ? null : LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    private void requirePrivateStorage() {
        if(!"local".equals(storage.getType()) && !privateObjectStorageConfirmed)
            throw BusinessException.i18n("export.private_storage_required");
    }
}
