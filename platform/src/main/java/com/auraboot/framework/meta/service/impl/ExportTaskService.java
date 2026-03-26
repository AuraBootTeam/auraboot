package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.meta.dto.ExportTaskDTO;
import com.auraboot.framework.meta.dto.NamedQueryDataExportRequest;
import com.auraboot.framework.meta.entity.ExportTask;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.entity.NamedQueryPolicy;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.ExportTaskMapper;
import com.auraboot.framework.meta.mapper.NamedQueryFieldMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
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
    private final NamedQueryFieldMapper namedQueryFieldMapper;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    /** Dedicated export directory — isolated from OS temp */
    private static final java.nio.file.Path EXPORT_DIR;
    static {
        EXPORT_DIR = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "aura-exports");
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
        NamedQuery query = namedQueryMapper.findByCode(queryCode);
        if (query == null) {
            throw new MetaServiceException("Named query not found: " + queryCode);
        }
        if (!query.isExecutable()) {
            throw new MetaServiceException("Named query is not executable: " + queryCode);
        }

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

        try {
            task.setRequestParams(objectMapper.valueToTree(request));
        } catch (Exception e) {
            log.warn("Failed to serialize request params", e);
        }

        exportTaskMapper.insert(task);

        // Kick off async processing
        processExportAsync(task.getId(), tenantId);

        return toDTO(task);
    }

    /**
     * Get export task status.
     */
    public ExportTaskDTO getTaskStatus(String taskPid) {
        ExportTask task = exportTaskMapper.findByPid(taskPid);
        if (task == null) {
            throw new MetaServiceException("Export task not found: " + taskPid);
        }
        return toDTO(task);
    }

    /**
     * Get the file key (path) for a completed export task.
     */
    public String getFileKey(String taskPid) {
        ExportTask task = exportTaskMapper.findByPid(taskPid);
        return task != null ? task.getFileKey() : null;
    }

    /**
     * Get recent export tasks for a query.
     */
    public List<ExportTaskDTO> getRecentTasks(String queryCode, int limit) {
        return exportTaskMapper.findByQueryCode(queryCode, limit).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * Async export processing.
     */
    @Async("exportTaskExecutor")
    public void processExportAsync(Long taskId, Long tenantId) {
        ExportTask task = exportTaskMapper.selectById(taskId);
        if (task == null) return;

        try {
            task.setStatus(ExportTask.STATUS_RUNNING);
            exportTaskMapper.updateById(task);

            NamedQuery query = namedQueryMapper.findByCode(task.getQueryCode());
            if (query == null) {
                failTask(task, "Named query not found");
                return;
            }

            List<NamedQueryField> fields = namedQueryFieldMapper.findByQueryCode(tenantId, task.getQueryCode());

            // Build SELECT
            List<String> selectColumns = fields.stream()
                    .map(f -> f.getColumnExpr() + " AS " + f.getFieldCode())
                    .toList();
            if (selectColumns.isEmpty()) {
                selectColumns = List.of("*");
            }

            StringBuilder sql = new StringBuilder("SELECT ");
            sql.append(String.join(", ", selectColumns));
            sql.append(" FROM ").append(query.getFromSql());

            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("tenantId", tenantId);

            // Count total rows
            String countSql = "SELECT COUNT(*) FROM (" + sql + ") AS _count";
            Long total = dynamicDataMapper.countByQuery(countSql, params);
            task.setTotalRows(total);

            // Apply export limit from policy
            NamedQueryPolicy policy = query.getPolicy() != null ? query.getPolicy() : new NamedQueryPolicy();
            int exportLimit = policy.getExportMaxRows() != null ? policy.getExportMaxRows() : 50000;
            long effectiveTotal = Math.min(total, exportLimit);

            sql.append(" LIMIT ").append(effectiveTotal);

            // Execute and write to file
            List<java.util.Map<String, Object>> data = dynamicDataMapper.selectByQuery(sql.toString(), params);

            task.setProcessedRows((long) data.size());
            task.setProgress(100);

            // Generate file
            List<String> fieldCodes = fields.stream().map(NamedQueryField::getFieldCode).toList();
            String fileName = task.getQueryCode() + "_export_" + task.getPid();

            java.nio.file.Path tempFile;
            String format = task.getFormat() != null ? task.getFormat() : "excel";
            switch (format) {
                case "csv":
                    tempFile = exportAsCsv(data, fieldCodes, fileName);
                    break;
                case "json":
                    tempFile = exportAsJson(data, fieldCodes, fileName);
                    break;
                default:
                    tempFile = exportAsExcel(data, fieldCodes, fileName);
                    break;
            }

            task.setFileKey(tempFile.toString());
            task.setFileSize(java.nio.file.Files.size(tempFile));
            task.setStatus(ExportTask.STATUS_COMPLETED);
            task.setCompletedAt(Instant.now());
            exportTaskMapper.updateById(task);

            log.info("Export task completed: pid={}, rows={}, size={}",
                    task.getPid(), data.size(), task.getFileSize());

        } catch (Exception e) {
            log.error("Export task failed: taskId={}", taskId, e);
            failTask(task, e.getMessage());
        }
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

        if (ExportTask.STATUS_COMPLETED.equals(entity.getStatus()) && entity.getFileKey() != null) {
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

    // ==================== Export file generators ====================

    private java.nio.file.Path exportAsExcel(List<java.util.Map<String, Object>> data,
                                              List<String> fields, String fileName) throws java.io.IOException {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(EXPORT_DIR, fileName, ".xlsx");
        // Use SXSSFWorkbook for streaming writes (keeps only 100 rows in memory)
        try (org.apache.poi.xssf.streaming.SXSSFWorkbook workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100)) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Data");
            int rowNum = 0;

            // Header
            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(rowNum++);
            for (int i = 0; i < fields.size(); i++) {
                headerRow.createCell(i).setCellValue(fields.get(i));
            }

            // Data rows — streamed, not all in memory
            for (java.util.Map<String, Object> row : data) {
                org.apache.poi.ss.usermodel.Row dataRow = sheet.createRow(rowNum++);
                for (int i = 0; i < fields.size(); i++) {
                    Object val = row.get(fields.get(i));
                    org.apache.poi.ss.usermodel.Cell cell = dataRow.createCell(i);
                    if (val != null) {
                        if (val instanceof Number) {
                            cell.setCellValue(((Number) val).doubleValue());
                        } else {
                            cell.setCellValue(val.toString());
                        }
                    }
                }
            }

            try (java.io.OutputStream os = java.nio.file.Files.newOutputStream(tempFile)) {
                workbook.write(os);
            }
            workbook.dispose(); // Clean up temp files created by SXSSFWorkbook
        }
        return tempFile;
    }

    private java.nio.file.Path exportAsCsv(List<java.util.Map<String, Object>> data,
                                             List<String> fields, String fileName) throws java.io.IOException {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(EXPORT_DIR, fileName, ".csv");
        try (java.io.BufferedWriter writer = java.nio.file.Files.newBufferedWriter(tempFile)) {
            writer.write(String.join(",", fields));
            writer.newLine();
            for (java.util.Map<String, Object> row : data) {
                List<String> values = fields.stream()
                        .map(f -> {
                            Object val = row.get(f);
                            if (val == null) return "";
                            String str = val.toString();
                            if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
                                return "\"" + str.replace("\"", "\"\"") + "\"";
                            }
                            return str;
                        })
                        .toList();
                writer.write(String.join(",", values));
                writer.newLine();
            }
        }
        return tempFile;
    }

    private java.nio.file.Path exportAsJson(List<java.util.Map<String, Object>> data,
                                              List<String> fields, String fileName) throws java.io.IOException {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(EXPORT_DIR, fileName, ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), data);
        return tempFile;
    }
}
