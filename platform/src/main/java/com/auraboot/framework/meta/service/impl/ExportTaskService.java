package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ExportTaskDTO;
import com.auraboot.framework.meta.dto.NamedQueryDataExportRequest;
import com.auraboot.framework.meta.entity.ExportTask;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.ExportTaskMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.dto.ExportResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Async export task service.
 * Submits export tasks and processes them asynchronously.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportTaskService {

    private final ExportTaskMapper exportTaskMapper;
    private final NamedQueryMapper namedQueryMapper;
    private final NamedQueryService namedQueryService;
    private final ObjectMapper objectMapper;

    @Autowired
    @Qualifier("exportTaskExecutor")
    private Executor exportTaskExecutor;

    /** Dedicated export directory — isolated from OS temp */
    private static final java.nio.file.Path EXPORT_DIR;
    static {
        EXPORT_DIR = java.nio.file.Paths.get(System.getProperty("user.home"), ".auraboot", "exports");
        try {
            java.nio.file.Files.createDirectories(EXPORT_DIR);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create export directory", e);
        }
    }

    /**
     * Submit an async export task.
     * Captures tenant context at submission time since @Async threads lack MetaContext.
     */
    public ExportTaskDTO submitExport(String queryCode, NamedQueryDataExportRequest request,
                                       Long tenantId, Long userId) {
        if (!java.util.Objects.equals(tenantId, MetaContext.getCurrentTenantId())
                || userId == null || !userId.equals(MetaContext.getCurrentUserId())) {
            throw new MetaServiceException("Authenticated export owner is required");
        }
        NamedQuery query = namedQueryMapper.findByCode(queryCode);
        if (query == null) {
            throw new MetaServiceException("Named query not found: " + queryCode);
        }
        if (!query.isExecutable()) {
            throw new MetaServiceException("Named query is not executable: " + queryCode);
        }

        ExportTask task = newTask(queryCode, request, tenantId, userId);

        exportTaskMapper.insert(task);

        // Kick off async processing
        processExportAsync(task.getId(), tenantId);

        return toDTO(task);
    }

    private ExportTask newTask(String queryCode, NamedQueryDataExportRequest request,
                               Long tenantId, Long userId) {
        ExportTask task = new ExportTask();
        task.setPid(UlidGenerator.generate());
        task.setTenantId(tenantId);
        task.setQueryCode(queryCode);
        task.setStatus(ExportTask.STATUS_PENDING);
        task.setProgress(0);
        task.setProcessedRows(0L);
        task.setFormat(request.getFormat() != null ? request.getFormat().name() : "excel");
        task.setCreatedBy(userId);
        task.setCreatedAt(Instant.now());
        task.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));

        task.setRequestParams(objectMapper.valueToTree(request));

        return task;
    }

    /** Synchronous exports use the same owner-scoped artifact lifecycle as background exports. */
    public ExportResult exportSync(String queryCode, NamedQueryDataExportRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            throw new MetaServiceException("Authenticated export owner is required");
        }
        ExportResult result = namedQueryService.exportData(queryCode, request);
        if (!Boolean.TRUE.equals(result.getSuccess())) return result;
        ExportTask task = newTask(queryCode, request, tenantId, userId);
        try {
            completeArtifact(task, result);
        } catch (java.io.IOException e) {
            throw new MetaServiceException("Failed to store export artifact");
        }
        exportTaskMapper.insert(task);
        result.setDownloadUrl(toDTO(task).getDownloadUrl());
        result.setFilePath(null);
        return result;
    }

    /**
     * Get export task status.
     */
    public ExportTaskDTO getTaskStatus(String taskPid) {
        ExportTask task = exportTaskMapper.findByPid(taskPid);
        if (task == null || !belongsToCurrentOwner(task)) {
            throw new MetaServiceException("Export task not found: " + taskPid);
        }
        return toDTO(task);
    }

    /**
     * Get the file key (path) for a completed export task.
     */
    public String getFileKey(String taskPid) {
        ExportTask task = exportTaskMapper.findByPid(taskPid);
        if (task == null || !belongsToCurrentOwner(task) || !isDownloadable(task)) {
            return null;
        }
        return task.getFileKey();
    }

    /** Global task identifiers must always be scoped to the authenticated owner. */
    private boolean belongsToCurrentOwner(ExportTask task) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        return tenantId != null && userId != null
                && tenantId.equals(task.getTenantId()) && userId.equals(task.getCreatedBy());
    }

    /** Get recent exports belonging to the authenticated user within the tenant. */
    public List<ExportTaskDTO> getRecentTasks(String queryCode, int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            throw new MetaServiceException("Authenticated export owner is required");
        }
        return exportTaskMapper.findByQueryCode(queryCode, tenantId, userId, limit).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * Async export processing.
     */
    public void processExportAsync(Long taskId, Long tenantId) {
        if (!java.util.Objects.equals(tenantId, MetaContext.getCurrentTenantId())) {
            throw new MetaServiceException("Authenticated export tenant is required");
        }
        ExportTask task = exportTaskMapper.selectById(taskId);
        if (task == null) return;
        if (!belongsToCurrentOwner(task)) {
            throw new MetaServiceException("Export task is unavailable to this user");
        }
        try {
            exportTaskExecutor.execute(() -> processExport(taskId));
        } catch (RejectedExecutionException rejected) {
            failTask(task, "Export capacity is exhausted");
            throw new MetaServiceException("Export capacity is exhausted");
        }
    }

    private void processExport(Long taskId) {
        ExportTask task = exportTaskMapper.selectById(taskId);
        if (task == null) return;
        if (!belongsToCurrentOwner(task)) {
            throw new MetaServiceException("Export worker owner does not match task");
        }
        try {
            task.setStatus(ExportTask.STATUS_RUNNING);
            exportTaskMapper.updateById(task);
            NamedQueryDataExportRequest request = objectMapper.treeToValue(
                    task.getRequestParams(), NamedQueryDataExportRequest.class);
            ExportResult result = namedQueryService.exportData(task.getQueryCode(), request);
            if (!Boolean.TRUE.equals(result.getSuccess())) {
                failTask(task, result.getErrorMessage());
                return;
            }
            completeArtifact(task, result);
            exportTaskMapper.updateById(task);
        } catch (Exception e) {
            log.error("Export task failed: taskId={}", taskId, e);
            failTask(task, e.getMessage());
        }
    }

    private void completeArtifact(ExportTask task, ExportResult result) throws java.io.IOException {
            java.nio.file.Path source = java.nio.file.Path.of(result.getFilePath());
            String extension = source.getFileName().toString();
            extension = extension.substring(extension.lastIndexOf('.'));
            java.nio.file.Path artifact = EXPORT_DIR.resolve(task.getPid() + extension);
            java.nio.file.Files.move(source, artifact);
            task.setFileKey(artifact.toString());
            task.setFileSize(result.getFileSize());
            task.setFormat(result.getFormat());
            task.setTotalRows(result.getRecordCount());
            task.setProcessedRows(result.getRecordCount());
            task.setProgress(100);
            task.setStatus(ExportTask.STATUS_COMPLETED);
            task.setCompletedAt(Instant.now());
    }

    private boolean isDownloadable(ExportTask task) {
        return ExportTask.STATUS_COMPLETED.equals(task.getStatus())
                && task.getExpiresAt() != null && task.getExpiresAt().isAfter(Instant.now())
                && task.getFileKey() != null;
    }

    /**
     * Clean up expired export files.
     */
    @Scheduled(fixedDelay = 3600000) // Every hour
    public void cleanupExpiredTasks() {
        List<ExportTask> expired = exportTaskMapper.findExpired(Instant.now());
        for (ExportTask task : expired) {
            try {
                if (task.getFileKey() != null) {
                    java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(task.getFileKey()));
                }
                task.setStatus(ExportTask.STATUS_EXPIRED);
                exportTaskMapper.updateById(task);
            } catch (Exception e) {
                log.warn("Failed to cleanup expired task: pid={}", task.getPid(), e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("Cleaned up {} expired export tasks", expired.size());
        }
    }

    // ==================== Private helpers ====================

    private void failTask(ExportTask task, String message) {
        task.setStatus(ExportTask.STATUS_FAILED);
        task.setErrorMessage(message);
        task.setCompletedAt(Instant.now());
        exportTaskMapper.updateById(task);
    }

    private ExportTaskDTO toDTO(ExportTask entity) {
        ExportTaskDTO dto = new ExportTaskDTO();
        dto.setPid(entity.getPid());
        dto.setQueryCode(entity.getQueryCode());
        dto.setStatus(entity.getStatus());
        dto.setProgress(entity.getProgress());
        dto.setTotalRows(entity.getTotalRows());
        dto.setProcessedRows(entity.getProcessedRows());
        dto.setFileSize(entity.getFileSize());
        dto.setFormat(entity.getFormat());
        dto.setErrorMessage(entity.getErrorMessage());

        if (isDownloadable(entity)) {
            dto.setDownloadUrl("/api/meta/named-queries/export-tasks/" + entity.getPid() + "/download");
        }

        dto.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
        dto.setCompletedAt(toLocalDateTime(entity.getCompletedAt()));
        dto.setExpiresAt(toLocalDateTime(entity.getExpiresAt()));
        return dto;
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

}
