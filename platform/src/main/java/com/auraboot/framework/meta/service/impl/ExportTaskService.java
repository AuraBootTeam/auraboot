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
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.dto.ExportResult;
import com.auraboot.framework.meta.dto.DataExportRequest;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
    private final StorageProvider storageProvider;
    private final DynamicDataService dynamicDataService;

    @Autowired
    @Qualifier("exportTaskExecutor")
    private Executor exportTaskExecutor;

    public record ExportArtifactDownload(java.io.InputStream content, long size, String extension) {}

    /**
     * Submit an async export task.
     * Captures tenant context at submission time as an execution fence; the
     * exportTaskExecutor also propagates MetaContext via TenantAwareTaskDecorator.
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

        if (exportTaskMapper.insert(task) != 1) {
            throw new MetaServiceException("Failed to persist export task");
        }

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

        com.fasterxml.jackson.databind.node.ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("sourceType", "named-query");
        metadata.set("request", objectMapper.valueToTree(request));
        task.setRequestParams(metadata);

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
        if (exportTaskMapper.insert(task) != 1) {
            deleteSourceFile(result.getFilePath());
            throw new MetaServiceException("Failed to persist export task before artifact storage");
        }
        try {
            completeArtifact(task, result);
            persistCompleted(task);
        } catch (java.io.IOException | RuntimeException e) {
            cleanupArtifact(task);
            failTask(task, "Failed to store export artifact");
            throw new MetaServiceException("Failed to store export artifact");
        }
        result.setDownloadUrl(toDTO(task).getDownloadUrl());
        result.setFilePath(null);
        return result;
    }

    /** Register a synchronous model-list export in the same durable artifact lifecycle. */
    public ExportTaskDTO registerModelExport(String modelCode, String profileCode,
                                             DataExportRequest request,
                                             ExportResult result) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            throw new MetaServiceException("Authenticated export owner is required");
        }
        if (!Boolean.TRUE.equals(result.getSuccess()) || result.getFilePath() == null) {
            throw new MetaServiceException("A completed model export is required");
        }
        ExportTask task = new ExportTask();
        task.setPid(UlidGenerator.generate());
        task.setTenantId(tenantId);
        task.setQueryCode("model:" + modelCode);
        task.setStatus(ExportTask.STATUS_PENDING);
        task.setProgress(0);
        task.setProcessedRows(0L);
        task.setFormat(request.getFormat() == null ? "EXCEL" : request.getFormat().name());
        task.setCreatedBy(userId);
        task.setCreatedAt(Instant.now());
        task.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        com.fasterxml.jackson.databind.node.ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("sourceType", "model");
        metadata.put("modelCode", modelCode);
        metadata.put("profileCode", profileCode);
        metadata.set("request", objectMapper.valueToTree(request));
        task.setRequestParams(metadata);
        if (exportTaskMapper.insert(task) != 1) {
            deleteSourceFile(result.getFilePath());
            throw new MetaServiceException("Failed to persist model export task before artifact storage");
        }
        try {
            completeArtifact(task, result);
            persistCompleted(task);
        } catch (java.io.IOException | RuntimeException e) {
            cleanupArtifact(task);
            failTask(task, "Failed to store model export artifact");
            throw new MetaServiceException("Failed to store model export artifact");
        }
        return toDTO(task);
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
        NamedQueryDataExportRequest request = objectMapper.convertValue(
                task.getRequestParams().get("request"), NamedQueryDataExportRequest.class);
        if (request == null) throw new MetaServiceException("Export authorization request is missing");
        namedQueryService.authorizeExportDownload(task.getQueryCode(), request,
                task.getRequestParams().get("definition"),
                task.getRequestParams().hasNonNull("rowDigest")
                        ? task.getRequestParams().get("rowDigest").asText() : null);
        return task.getFileKey();
    }

    /** Resolve private artifact content only after the same owner and source re-authorization. */
    public ExportArtifactDownload openArtifact(String taskPid) {
        String key = getFileKey(taskPid);
        if (key == null || !storageProvider.exists(key)) return null;
        ExportTask task = exportTaskMapper.findByPid(taskPid);
        String extension = key.substring(key.lastIndexOf('.'));
        return new ExportArtifactDownload(storageProvider.download(key),
                task.getFileSize() == null ? 0L : task.getFileSize(), extension);
    }

    /** Model endpoints re-check live model permission before calling this owner-scoped resolver. */
    public ExportArtifactDownload openModelArtifact(String taskPid, String modelCode) {
        ExportTask task = exportTaskMapper.findByPid(taskPid);
        if (task == null || !belongsToCurrentOwner(task) || !isDownloadable(task)
                || !String.valueOf(task.getQueryCode()).equals("model:" + modelCode)) {
            return null;
        }
        String key = task.getFileKey();
        DataExportRequest request = objectMapper.convertValue(
                task.getRequestParams().get("request"), DataExportRequest.class);
        String storedDigest = task.getRequestParams().path("rowDigest").textValue();
        if (request == null || storedDigest == null) {
            throw new MetaServiceException("Export authorization evidence is missing");
        }
        ExportResult current = dynamicDataService.exportData(modelCode, request);
        try {
            if (!Boolean.TRUE.equals(current.getSuccess())
                    || !storedDigest.equals(current.getRowSetDigest())) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Export data no longer matches current permissions; create a new export");
            }
        } finally {
            if (current.getFilePath() != null) {
                try {
                    java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(current.getFilePath()));
                } catch (java.io.IOException cleanupFailure) {
                    log.warn("Failed to delete model export reauthorization file", cleanupFailure);
                }
            }
        }
        if (!storageProvider.exists(key)) return null;
        return new ExportArtifactDownload(storageProvider.download(key),
                task.getFileSize() == null ? 0L : task.getFileSize(),
                key.substring(key.lastIndexOf('.')));
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
                    task.getRequestParams().get("request"), NamedQueryDataExportRequest.class);
            ExportResult result = namedQueryService.exportData(task.getQueryCode(), request);
            if (!Boolean.TRUE.equals(result.getSuccess())) {
                failTask(task, result.getErrorMessage());
                return;
            }
            completeArtifact(task, result);
            persistCompleted(task);
        } catch (Exception e) {
            log.error("Export task failed: taskId={}", taskId, e);
            cleanupArtifact(task);
            failTask(task, e.getMessage());
        }
    }

    private void completeArtifact(ExportTask task, ExportResult result) throws java.io.IOException {
        com.fasterxml.jackson.databind.node.ObjectNode metadata =
                (com.fasterxml.jackson.databind.node.ObjectNode) task.getRequestParams();
        if ("named-query".equals(metadata.path("sourceType").asText())) {
            if (result.getDefinitionSnapshot() == null) {
                throw new MetaServiceException("Export definition evidence is missing");
            }
            metadata.set("definition", result.getDefinitionSnapshot());
            if (result.getRowSetDigest() == null) {
                throw new MetaServiceException("Export row digest evidence is missing");
            }
            metadata.put("rowDigest", result.getRowSetDigest());
        }
        if (result.getRowSetDigest() != null) {
            metadata.put("rowDigest", result.getRowSetDigest());
        }
            java.nio.file.Path source = java.nio.file.Path.of(result.getFilePath());
            String extension = source.getFileName().toString();
            extension = extension.substring(extension.lastIndexOf('.'));
            String artifactKey = "exports/" + task.getTenantId() + "/" + task.getCreatedBy()
                    + "/" + task.getPid() + extension;
            task.setFileKey(artifactKey);
            metadata.put("checksumSha256", sha256(source));
            try (java.io.InputStream content = java.nio.file.Files.newInputStream(source)) {
                storageProvider.upload(artifactKey, content, java.nio.file.Files.size(source),
                        contentType(extension));
            } finally {
                java.nio.file.Files.deleteIfExists(source);
            }
            task.setFileSize(result.getFileSize());
            task.setFormat(result.getFormat());
            task.setTotalRows(result.getRecordCount());
            task.setProcessedRows(result.getRecordCount());
            task.setProgress(100);
            task.setStatus(ExportTask.STATUS_COMPLETED);
            task.setCompletedAt(Instant.now());
    }

    private void persistCompleted(ExportTask task) {
        if (exportTaskMapper.updateById(task) != 1) {
            throw new MetaServiceException("Failed to persist completed export task");
        }
    }

    private void cleanupArtifact(ExportTask task) {
        if (task.getFileKey() == null) return;
        try {
            storageProvider.delete(task.getFileKey());
        } catch (RuntimeException cleanupFailure) {
            log.warn("Failed to remove incomplete export artifact {}", task.getPid(), cleanupFailure);
        }
        task.setFileKey(null);
        task.setFileSize(null);
    }

    private static void deleteSourceFile(String filePath) {
        if (filePath == null) return;
        try {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(filePath));
        } catch (java.io.IOException ignored) {
            // Best-effort cleanup when no durable task exists to own the temporary file.
        }
    }

    private static String sha256(java.nio.file.Path source) throws java.io.IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = java.nio.file.Files.newInputStream(source)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private boolean isDownloadable(ExportTask task) {
        return ExportTask.STATUS_COMPLETED.equals(task.getStatus())
                && task.getExpiresAt() != null && task.getExpiresAt().isAfter(Instant.now())
                && task.getFileKey() != null;
    }

    private static String contentType(String extension) {
        return switch (extension) {
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".csv" -> "text/csv; charset=UTF-8";
            case ".json" -> "application/json; charset=UTF-8";
            default -> "application/octet-stream";
        };
    }

    /**
     * Clean up expired export files.
     */
    @Scheduled(fixedDelay = 3600000) // Every hour
    public void cleanupExpiredTasks() {
        // @Scheduled thread: explicit scope (tenant-exemption W3).
        MetaContext.runWithoutTenantFilter(() -> {
        List<ExportTask> expired = exportTaskMapper.findExpired(Instant.now());
        for (ExportTask task : expired) {
            try {
                if (task.getFileKey() != null) {
                    storageProvider.delete(task.getFileKey());
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
        });
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

        if (isDownloadable(entity) && !String.valueOf(entity.getQueryCode()).startsWith("model:")) {
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
