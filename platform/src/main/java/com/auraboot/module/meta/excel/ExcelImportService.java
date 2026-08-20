package com.auraboot.module.meta.excel;

import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.TypeSystemManager;
import com.auraboot.module.meta.excel.entity.ImportJob;
import com.auraboot.module.meta.excel.mapper.ImportJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.constant.ResponseCode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;

/**
 * Reusable Excel import service that parses .xlsx files, validates rows,
 * and inserts data via DynamicDataService.
 *
 * <p>Usage flow:
 * <ol>
 *   <li>Parse Excel stream into raw row maps (header row = column names)</li>
 *   <li>Validate rows (extensible; currently checks for empty rows)</li>
 *   <li>If dryRun, return validation results without persisting</li>
 *   <li>Insert each row via DynamicDataService.create()</li>
 * </ol>
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    static final String ROW_WRITE_FAILED_MESSAGE =
            "Import row could not be saved. Check the field values and try again.";

    /** Number of rows per batch for import insert operations. */
    static final int BATCH_SIZE = 500;
    /** Row count threshold above which import runs asynchronously. */
    static final int DEFAULT_ASYNC_THRESHOLD = 1000;
    static final int MAX_PERSISTED_ROW_ERRORS = 100;
    private static final TypeReference<List<ImportValidationError>> IMPORT_ERRORS_TYPE =
            new TypeReference<>() { };

    private final DynamicDataService dynamicDataService;
    private final MetaModelService metaModelService;
    private final ImportJobMapper importJobMapper;
    private final ExcelImportPolicyResolver policyResolver;
    private final CommandExecutor commandExecutor;
    private final ExcelReferenceResolver referenceResolver;
    private final TypeSystemManager typeSystemManager;
    private final ExcelImportErrorReportService errorReportService;
    private final ObjectMapper objectMapper;

    @Value("${auraboot.excel-import.async-threshold:1000}")
    private int asyncThreshold = DEFAULT_ASYNC_THRESHOLD;

    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2);
    private final Map<String, AsyncImportStatus> asyncTasks = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> importEmitters = new ConcurrentHashMap<>();

    public int getAsyncThreshold() {
        return Math.max(1, asyncThreshold);
    }

    @PreDestroy
    void shutdownAsyncResources() {
        asyncExecutor.shutdownNow();
        for (String taskId : List.copyOf(importEmitters.keySet())) {
            closeEmitters(taskId);
        }
    }

    /**
     * Status of an async import task.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class AsyncImportStatus {
        private String taskId;
        private String modelCode;
        @JsonIgnore
        private Long tenantId;
        @JsonIgnore
        private Long createdBy;
        private String status; // RUNNING, COMPLETED, FAILED
        private int totalRows;
        private int processedRows;
        private ExcelImportResult result;
    }

    /**
     * Import an Excel file into the specified model.
     *
     * @param modelCode   the target model code
     * @param excelStream input stream of the .xlsx file
     * @param options      import options (skipErrors, dryRun, dateFormat)
     * @return import result with success/error counts
     */
    public ExcelImportResult importExcel(String modelCode, InputStream excelStream, ImportOptions options) {
        if (options == null) {
            options = new ImportOptions();
        }

        // 1. Parse Excel
        List<Map<String, String>> rawRows;
        try {
            rawRows = parseExcel(excelStream, options.getDateFormat());
        } catch (IOException e) {
            log.error("Failed to parse Excel file: {}", e.getMessage());
            return ExcelImportResult.withErrors(
                    List.of(new ImportValidationError(0, null, "Failed to parse Excel: " + e.getMessage())),
                    0);
        }

        if (rawRows.isEmpty()) {
            return ExcelImportResult.success(0, 0, List.of());
        }

        // 2. Resolve header mapping (displayName -> fieldCode)
        List<FieldDefinition> fieldDefs = metaModelService.getModelFields(modelCode);
        Map<String, String> headerMapping = resolveHeaderMapping(
                new ArrayList<>(rawRows.get(0).keySet()), fieldDefs);

        // Remap row keys using resolved mapping
        List<Map<String, String>> mappedRows = new ArrayList<>();
        for (Map<String, String> row : rawRows) {
            Map<String, String> mapped = new LinkedHashMap<>();
            for (var entry : row.entrySet()) {
                String mappedKey = headerMapping.getOrDefault(entry.getKey(), entry.getKey());
                mapped.put(mappedKey, entry.getValue());
            }
            mappedRows.add(mapped);
        }

        List<ImportValidationError> referenceErrors = resolveReferenceValues(fieldDefs, mappedRows);
        if (!referenceErrors.isEmpty()) {
            return ExcelImportResult.withErrors(referenceErrors, mappedRows.size());
        }

        // 3. Validate
        List<ImportValidationError> errors = new ArrayList<>(validate(mappedRows));
        if (!errors.isEmpty() && !options.isSkipErrors()) {
            return ExcelImportResult.withErrors(errors, mappedRows.size());
        }

        // 4. DryRun check
        if (options.isDryRun()) {
            return ExcelImportResult.builder()
                    .totalRows(mappedRows.size())
                    .successCount(0)
                    .errorCount(0)
                    .errors(errors)
                    .hasErrors(!errors.isEmpty())
                    .build();
        }

        // 5. Execute the model's explicit INSERT / UPDATE import policy.
        int success = 0;
        int errorCount = 0;
        int createdCount = 0;
        int updatedCount = 0;
        ExcelImportPolicy policy = policyResolver.requireEnabled(modelCode);
        String mode = normalizeMode(options);
        String matchKey = options.getMatchKey();
        policyResolver.validateMode(policy, mode, matchKey);
        String importRunId = UniqueIdGenerator.generate();

        if ("update".equals(mode)) {
            // UPDATE never creates a missing record. This matches the Cordys import contract.
            for (int i = 0; i < mappedRows.size(); i++) {
                try {
                    Map<String, Object> rowData = convertRowValues(fieldDefs, mappedRows.get(i));
                    Object keyValue = rowData.get(matchKey);
                    String existingId = findExistingRecordId(modelCode, matchKey, keyValue);

                    if (existingId == null) {
                        throw new BusinessException("No existing record matches " + matchKey + "=" + keyValue);
                    }
                    executeUpdate(policy, modelCode, existingId, rowData, i + 2, importRunId);
                    updatedCount++;
                    success++;
                } catch (Exception e) {
                    // CATCH: per-row import tolerance — report this row and keep processing only
                    // when skipErrors is enabled; retain the full cause in server logs.
                    log.warn("Excel update row failed: model={}, row={}", modelCode, i + 2, e);
                    errorCount++;
                    errors.add(new ImportValidationError(i + 2, null, safeMessage(e)));
                    if (!options.isSkipErrors()) {
                        break;
                    }
                }
            }
        } else if (policy.getCreateCommand() != null) {
            for (int i = 0; i < mappedRows.size(); i++) {
                try {
                    executeCreate(policy, modelCode, convertRowValues(fieldDefs, mappedRows.get(i)),
                            i + 2, importRunId);
                    success++;
                    createdCount++;
                } catch (Exception e) {
                    // CATCH: per-row import tolerance — report this row and keep processing only
                    // when skipErrors is enabled; retain the full cause in server logs.
                    log.warn("Excel create row failed: model={}, row={}", modelCode, i + 2, e);
                    errorCount++;
                    errors.add(new ImportValidationError(i + 2, null, safeMessage(e)));
                    if (!options.isSkipErrors()) {
                        break;
                    }
                }
            }
        } else {
            // Pure CRUD models without a command keep the existing batch fast path.
            for (int batchStart = 0; batchStart < mappedRows.size(); batchStart += BATCH_SIZE) {
                int batchEnd = Math.min(batchStart + BATCH_SIZE, mappedRows.size());
                List<Map<String, String>> batch = mappedRows.subList(batchStart, batchEnd);

                try {
                    List<Map<String, Object>> batchData = new ArrayList<>();
                    for (Map<String, String> row : batch) {
                        batchData.add(allowedPayload(convertRowValues(fieldDefs, row),
                                policy.getCreateFields()));
                    }
                    dynamicDataService.batchCreate(modelCode, batchData);
                    success += batch.size();
                    createdCount += batch.size();
                } catch (Exception batchError) {
                    // Batch failed — fall back to per-row for error isolation
                    log.warn("Batch insert failed for model {}; falling back to per-row",
                            modelCode, batchError);
                    for (int i = 0; i < batch.size(); i++) {
                        try {
                            dynamicDataService.create(modelCode,
                                    allowedPayload(convertRowValues(fieldDefs, batch.get(i)),
                                            policy.getCreateFields()));
                            success++;
                            createdCount++;
                        } catch (Exception e) {
                            // CATCH: per-row fallback isolates a bad row after the batch failed.
                            log.warn("Excel CRUD row failed: model={}, row={}",
                                    modelCode, batchStart + i + 2, e);
                            errorCount++;
                            errors.add(new ImportValidationError(batchStart + i + 2, null, safeMessage(e)));
                            if (!options.isSkipErrors()) {
                                return ExcelImportResult.builder()
                                        .totalRows(success + errorCount)
                                        .successCount(success).errorCount(errorCount)
                                        .createdCount(createdCount).updatedCount(updatedCount)
                                        .errors(errors).hasErrors(errorCount > 0).build();
                            }
                        }
                    }
                }
            }
        }

        return ExcelImportResult.builder()
                .totalRows(success + errorCount)
                .successCount(success).errorCount(errorCount)
                .createdCount(createdCount).updatedCount(updatedCount)
                .errors(errors).hasErrors(errorCount > 0).build();
    }

    /**
     * Find the pid of an existing record matching the given field value.
     * Returns null if no match is found; ambiguous or failed lookups fail closed.
     */
    private String findExistingRecordId(String modelCode, String fieldCode, Object fieldValue) {
        if (fieldValue == null || fieldValue.toString().isBlank()) return null;
        try {
            var condition = com.auraboot.framework.meta.dto.QueryCondition.builder()
                    .fieldName(fieldCode)
                    .operator(com.auraboot.framework.meta.dto.QueryCondition.Operator.EQ)
                    .value(fieldValue)
                    .build();
            var request = com.auraboot.framework.meta.dto.DynamicQueryRequest.builder()
                    .pageNum(1).pageSize(2)
                    .conditions(List.of(condition))
                    .build();
            var result = dynamicDataService.list(modelCode, request);
            if (result != null && result.getRecords() != null && result.getRecords().size() > 1) {
                throw new BusinessException("Import match key is not unique: " + fieldCode + "=" + fieldValue);
            }
            if (result != null && result.getRecords() != null && !result.getRecords().isEmpty()) {
                Object pid = result.getRecords().get(0).get("pid");
                return pid != null ? pid.toString() : null;
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(
                    ResponseCode.BUSINESS_ERROR,
                    "Import match lookup failed for " + fieldCode + ": " + safeMessage(e),
                    e);
        }
        return null;
    }

    /**
     * Start an async import and return the task ID.
     * Creates an ImportJob record and emits SSE progress events during processing.
     */
    public String importExcelAsync(String modelCode, InputStream excelStream, ImportOptions options) throws IOException {
        return importExcelAsync(modelCode, excelStream, options, null);
    }

    public String importExcelAsync(String modelCode, InputStream excelStream, ImportOptions options,
                                   String fileName) throws IOException {
        String taskId = UniqueIdGenerator.generate();
        byte[] bytes = excelStream.readAllBytes();
        ExcelImportPolicy policy = policyResolver.requireEnabled(modelCode);
        String mode = normalizeMode(options);
        policyResolver.validateMode(policy, mode, options.getMatchKey());
        MetaContext.Snapshot contextSnapshot = MetaContext.snapshot();
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        // Create import job record
        ImportJob job = new ImportJob();
        job.setPid(taskId);
        job.setTenantId(tenantId);
        job.setModelCode(modelCode);
        job.setFileName(fileName);
        job.setStatus(StatusConstants.RUNNING);
        job.setImportMode(mode);
        job.setCreatedBy(userId);
        LocalDateTime now = utcNow();
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setDeletedFlag(false);
        if (importJobMapper.insert(job) != 1 || job.getId() == null) {
            throw new BusinessException("Failed to persist import task before execution");
        }
        Long jobId = job.getId();

        AsyncImportStatus status = new AsyncImportStatus();
        status.setTaskId(taskId);
        status.setModelCode(modelCode);
        status.setTenantId(tenantId);
        status.setCreatedBy(userId);
        status.setStatus(StatusConstants.RUNNING);
        asyncTasks.put(taskId, status);

        asyncExecutor.submit(() -> {
            try {
                MetaContext.restore(contextSnapshot);
                ExcelImportResult result = importExcelWithProgress(modelCode,
                        new java.io.ByteArrayInputStream(bytes), options, taskId, jobId);
                if (result.isHasErrors() && result.getErrors() != null && !result.getErrors().isEmpty()) {
                    result.setTaskId(taskId);
                    try {
                        String reportUrl = errorReportService.attachReport(
                                jobId, taskId, modelCode, bytes, result.getErrors(),
                                options.getReportLocale(), reportSelection(result, options));
                        result.setErrorReportUrl(reportUrl);
                    } catch (RuntimeException reportError) {
                        // CATCH: report storage is non-transactional and must not overwrite the
                        // truthful result of rows that may already have been committed.
                        log.error("Async import completed with row errors but its correction "
                                + "workbook could not be created: task={}, model={}",
                                taskId, modelCode, reportError);
                        result.setErrorReportFailed(true);
                    }
                }
                status.setResult(result);
                status.setProcessedRows(result.getTotalRows());
                status.setTotalRows(result.getTotalRows());
                status.setStatus(StatusConstants.COMPLETED);

                // Update import job
                updateImportJob(jobId, "completed", result);

                // Emit final completion event
                emitProgress(taskId, result.getTotalRows(), result.getTotalRows(),
                        result.getErrorCount(), "completed");
            } catch (Exception e) {
                // CATCH: top-level async boundary — no caller can observe this failure, so keep
                // the complete cause chain alongside the durable failed task state.
                log.error("Async import failed for task {}: {}", taskId, e.getMessage(), e);
                status.setStatus(StatusConstants.FAILED);
                status.setResult(ExcelImportResult.builder()
                        .hasErrors(true)
                        .errors(List.of(new ImportValidationError(0, null, safeMessage(e))))
                        .build());

                updateImportJobStatus(jobId, "failed");
                emitProgress(taskId, 0, 0, 0, "failed");
            } finally {
                MetaContext.clear();
                // Close all SSE emitters for this task
                closeEmitters(taskId);
                // Completed tasks are reconstructed from the durable job row. Keeping every
                // terminal result in memory would grow without bound on a long-lived node.
                asyncTasks.remove(taskId);
            }
        });

        log.info("Async import started: taskId={}, model={}, jobId={}", taskId, modelCode, jobId);
        return taskId;
    }

    /**
     * Import with progress reporting for SSE and import job updates.
     */
    private ExcelImportResult importExcelWithProgress(String modelCode, InputStream excelStream,
                                                       ImportOptions options, String taskId, Long jobId) {
        if (options == null) {
            options = new ImportOptions();
        }

        List<Map<String, String>> rawRows;
        try {
            rawRows = parseExcel(excelStream, options.getDateFormat());
        } catch (IOException e) {
            log.error("Failed to parse Excel file: {}", e.getMessage());
            return ExcelImportResult.withErrors(
                    List.of(new ImportValidationError(0, null, "Failed to parse Excel: " + e.getMessage())), 0);
        }

        if (rawRows.isEmpty()) {
            return ExcelImportResult.success(0, 0, List.of());
        }

        // Update job with total rows
        updateImportJobTotalRows(jobId, rawRows.size());

        // Resolve headers
        List<FieldDefinition> fieldDefs = metaModelService.getModelFields(modelCode);
        Map<String, String> headerMapping = resolveHeaderMapping(
                new ArrayList<>(rawRows.get(0).keySet()), fieldDefs);

        List<Map<String, String>> mappedRows = new ArrayList<>();
        for (Map<String, String> row : rawRows) {
            Map<String, String> mapped = new LinkedHashMap<>();
            for (var entry : row.entrySet()) {
                String mappedKey = headerMapping.getOrDefault(entry.getKey(), entry.getKey());
                mapped.put(mappedKey, entry.getValue());
            }
            mappedRows.add(mapped);
        }

        List<ImportValidationError> referenceErrors = resolveReferenceValues(fieldDefs, mappedRows);
        if (!referenceErrors.isEmpty()) {
            return ExcelImportResult.withErrors(referenceErrors, mappedRows.size());
        }

        List<ImportValidationError> errors = new ArrayList<>(validate(mappedRows));
        int success = 0;
        int errorCount = 0;
        int createdCount = 0;
        int updatedCount = 0;
        ExcelImportPolicy policy = policyResolver.requireEnabled(modelCode);
        String mode = normalizeMode(options);
        String matchKey = options.getMatchKey();
        policyResolver.validateMode(policy, mode, matchKey);

        // Async uses the same command-aware semantics as the synchronous path.
        for (int batchStart = 0; batchStart < mappedRows.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, mappedRows.size());
            List<Map<String, String>> batch = mappedRows.subList(batchStart, batchEnd);

            boolean stop = false;
            for (int i = 0; i < batch.size(); i++) {
                int rowNumber = batchStart + i + 2;
                try {
                    Map<String, Object> rowData = convertRowValues(fieldDefs, batch.get(i));
                    if ("update".equals(mode)) {
                        Object keyValue = rowData.get(matchKey);
                        String existingId = findExistingRecordId(modelCode, matchKey, keyValue);
                        if (existingId == null) {
                            throw new BusinessException("No existing record matches " + matchKey + "=" + keyValue);
                        }
                        executeUpdate(policy, modelCode, existingId, rowData, rowNumber, taskId);
                        updatedCount++;
                    } else {
                        executeCreate(policy, modelCode, rowData, rowNumber, taskId);
                        createdCount++;
                    }
                    success++;
                } catch (Exception e) {
                    // CATCH: per-row import tolerance — the async task must persist a truthful
                    // partial result instead of terminating without row-level evidence.
                    log.warn("Async Excel import row failed: task={}, model={}, row={}",
                            taskId, modelCode, rowNumber, e);
                    errorCount++;
                    errors.add(new ImportValidationError(rowNumber, null, safeMessage(e)));
                    if (!options.isSkipErrors()) {
                        stop = true;
                        break;
                    }
                }
            }

            // Emit progress after each batch
            emitProgress(taskId, success + errorCount, mappedRows.size(), errorCount, "running");
            updateImportJobProgress(jobId, success + errorCount, success, errorCount);
            if (stop) break;
        }

        return ExcelImportResult.builder()
                .totalRows(success + errorCount)
                .successCount(success).errorCount(errorCount)
                .createdCount(createdCount).updatedCount(updatedCount)
                .errors(errors).hasErrors(errorCount > 0).build();
    }

    /**
     * Get the status of an async import task.
     */
    public AsyncImportStatus getImportStatus(String taskId) {
        AsyncImportStatus status = asyncTasks.get(taskId);
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (status != null) {
            return Objects.equals(status.getTenantId(), tenantId)
                    && Objects.equals(status.getCreatedBy(), userId) ? status : null;
        }

        ImportJob job = importJobMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ImportJob>lambdaQuery()
                        .eq(ImportJob::getPid, taskId)
                        .eq(ImportJob::getTenantId, tenantId)
                        .eq(ImportJob::getCreatedBy, userId));
        if (job == null) return null;

        AsyncImportStatus restored = new AsyncImportStatus();
        restored.setTaskId(job.getPid());
        restored.setModelCode(job.getModelCode());
        restored.setTenantId(job.getTenantId());
        restored.setCreatedBy(job.getCreatedBy());
        restored.setTotalRows(valueOrZero(job.getTotalRows()));
        restored.setProcessedRows(valueOrZero(job.getProcessedRows()));
        String persistedStatus = job.getStatus() == null ? "failed" : job.getStatus().toLowerCase(Locale.ROOT);
        if (StatusConstants.RUNNING.equalsIgnoreCase(persistedStatus)) {
            // The executor is process-local. If a task is absent from memory after a restart,
            // it cannot still be running; fail closed instead of polling forever.
            persistedStatus = StatusConstants.FAILED;
            updateImportJobStatus(job.getId(), "failed");
            restored.setResult(ExcelImportResult.builder()
                    .totalRows(valueOrZero(job.getTotalRows()))
                    .successCount(valueOrZero(job.getSuccessRows()))
                    .errorCount(valueOrZero(job.getErrorRows()))
                    .errors(List.of(new ImportValidationError(
                            0, null, "Import was interrupted by a service restart")))
                    .hasErrors(true)
                    .build());
        } else {
            int successRows = valueOrZero(job.getSuccessRows());
            int errorRows = valueOrZero(job.getErrorRows());
            boolean updateMode = "update".equalsIgnoreCase(job.getImportMode());
            boolean reportExpired = errorRows > 0 && errorReportService.isReportExpired(job);
            restored.setResult(ExcelImportResult.builder()
                    .totalRows(valueOrZero(job.getTotalRows()))
                    .successCount(successRows)
                    .errorCount(errorRows)
                    .createdCount(updateMode ? 0 : successRows)
                    .updatedCount(updateMode ? successRows : 0)
                    .errors(readPersistedErrors(job))
                    .taskId(job.getPid())
                    .errorReportUrl(reportExpired ? null : job.getErrorReportUrl())
                    .errorReportFailed(errorRows > 0
                            && job.getErrorReportUrl() == null && !reportExpired)
                    .errorReportExpired(reportExpired)
                    .hasErrors(errorRows > 0 || StatusConstants.FAILED.equalsIgnoreCase(persistedStatus))
                    .build());
        }
        restored.setStatus(persistedStatus);
        return restored;
    }

    public AsyncImportStatus requireImportStatus(String modelCode, String taskId) {
        AsyncImportStatus status = getImportStatus(taskId);
        if (status == null || !Objects.equals(status.getModelCode(), modelCode)) {
            return null;
        }
        return status;
    }

    private String normalizeMode(ImportOptions options) {
        String mode = options == null ? null : options.getImportMode();
        return mode == null || mode.isBlank() ? "insert" : mode.toLowerCase(Locale.ROOT);
    }

    private ExcelImportErrorReportService.RowSelection reportSelection(
            ExcelImportResult result, ImportOptions options) {
        if (result.getSuccessCount() == 0
                && result.getErrorCount() < result.getTotalRows()) {
            return ExcelImportErrorReportService.RowSelection.ALL_ROWS;
        }
        return options.isSkipErrors()
                ? ExcelImportErrorReportService.RowSelection.ERROR_ROWS
                : ExcelImportErrorReportService.RowSelection.FROM_FIRST_ERROR;
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    private void executeCreate(ExcelImportPolicy policy, String modelCode,
                               Map<String, Object> rowData, int rowNumber, String importRunId) {
        Map<String, Object> payload = allowedPayload(rowData, policy.getCreateFields());
        if (policy.getCreateCommand() == null) {
            dynamicDataService.create(modelCode, payload);
            return;
        }
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(payload);
        request.setOperationType("create");
        request.setClientRequestId(importRequestId(modelCode, "insert", rowNumber, importRunId));
        request.setAuditContext(Map.of("source", "excel_import", "rowNumber", rowNumber));
        commandExecutor.execute(policy.getCreateCommand(), request);
    }

    private void executeUpdate(ExcelImportPolicy policy, String modelCode, String recordPid,
                               Map<String, Object> rowData, int rowNumber, String importRunId) {
        Map<String, Object> payload = allowedPayload(rowData, policy.getUpdateFields());
        if (policy.getUpdateCommand() == null) {
            dynamicDataService.update(modelCode, recordPid, payload);
            return;
        }
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(payload);
        request.setOperationType("update");
        request.setTargetRecordPid(recordPid);
        request.setClientRequestId(importRequestId(modelCode, "update", rowNumber, importRunId));
        request.setAuditContext(Map.of("source", "excel_import", "rowNumber", rowNumber));
        commandExecutor.execute(policy.getUpdateCommand(), request);
    }

    private Map<String, Object> allowedPayload(Map<String, Object> rowData, Set<String> allowedFields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rowData.entrySet()) {
            Object value = entry.getValue();
            // Empty template cells mean "omitted" for INSERT and "preserve" for UPDATE.
            // Passing "" to numeric/date/reference fields leaks spreadsheet representation
            // into the command/DB layer and can suppress command defaults or cause type errors.
            if (allowedFields.contains(entry.getKey())
                    && value != null
                    && (!(value instanceof String text) || !text.isBlank())) {
                payload.put(entry.getKey(), entry.getValue());
            }
        }
        return payload;
    }

    /** Convert validated spreadsheet strings to the Java types required by commands and SQL. */
    Map<String, Object> convertRowValues(List<FieldDefinition> fieldDefs, Map<String, String> row) {
        if (row == null || row.isEmpty()) {
            return Map.of();
        }
        Map<String, FieldDefinition> fieldsByCode = new HashMap<>();
        if (fieldDefs != null) {
            for (FieldDefinition field : fieldDefs) {
                if (field != null && field.getCode() != null) {
                    fieldsByCode.put(field.getCode(), field);
                }
            }
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : row.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            FieldDefinition field = fieldsByCode.get(entry.getKey());
            // Older models and compatibility fixtures can omit dataType. In that case the
            // import contract remains pass-through; conversion is only authoritative when
            // metadata explicitly declares a type.
            converted.put(entry.getKey(), field == null
                    || field.getDataType() == null
                    || field.getDataType().isBlank()
                    ? value : typeSystemManager.convertValue(value, field));
        }
        return converted;
    }

    private String importRequestId(String modelCode, String mode, int rowNumber, String importRunId) {
        return "excel:" + MetaContext.getCurrentTenantId() + ":" + modelCode + ":" + mode
                + ":" + importRunId + ":" + rowNumber;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (error instanceof BusinessException && message != null && !message.isBlank()
                && !looksLikeInfrastructureFailure(message)) {
            return message;
        }
        return ROW_WRITE_FAILED_MESSAGE;
    }

    private static boolean looksLikeInfrastructureFailure(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("org.postgresql")
                || normalized.contains("bad sql grammar")
                || normalized.contains("error updating database")
                || normalized.contains("dynamicdatamapper")
                || normalized.contains("mybatis")
                || normalized.contains("java.sql")
                || normalized.contains("sqlstate")
                || normalized.contains("###");
    }

    /** Resolve uploaded business reference values to their configured stored value. */
    List<ImportValidationError> resolveReferenceValues(List<FieldDefinition> fieldDefs,
                                                       List<Map<String, String>> rows) {
        if (fieldDefs == null || fieldDefs.isEmpty() || rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, FieldDefinition> references = new LinkedHashMap<>();
        for (FieldDefinition field : fieldDefs) {
            if ("reference".equalsIgnoreCase(field.getDataType())
                    && field.getRefTarget() != null
                    && field.getRefTarget().getTargetEntity() != null
                    && !field.getRefTarget().getTargetEntity().isBlank()) {
                references.put(field.getCode(), field);
            }
        }

        List<ImportValidationError> errors = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Map<String, String> row = rows.get(rowIndex);
            for (Map.Entry<String, FieldDefinition> reference : references.entrySet()) {
                String uploaded = row.get(reference.getKey());
                if (uploaded == null || uploaded.isBlank()) {
                    continue;
                }
                try {
                    row.put(reference.getKey(), referenceResolver.resolve(reference.getValue(), uploaded));
                } catch (Exception error) {
                    errors.add(new ImportValidationError(
                            rowIndex + 2, reference.getKey(), safeMessage(error)));
                }
            }
        }
        return errors;
    }

    /**
     * Parse an .xlsx stream into a list of row maps.
     * The first row is treated as the header (column names).
     */
    List<Map<String, String>> parseExcel(InputStream stream, String dateFormat) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        DataFormatter dataFormatter = new DataFormatter();

        try (Workbook workbook = new XSSFWorkbook(stream)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() < 2) {
                return rows; // no data rows
            }

            // Read header row
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return rows;

            List<String> headers = new ArrayList<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                Cell cell = headerRow.getCell(c);
                String headerValue = cell != null ? dataFormatter.formatCellValue(cell).trim() : "";
                headers.add(headerValue);
            }

            // Read data rows
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern(dateFormat != null ? dateFormat : "yyyy-MM-dd");
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                Map<String, String> rowMap = new LinkedHashMap<>();
                boolean hasData = false;

                for (int c = 0; c < headers.size(); c++) {
                    String header = headers.get(c);
                    if (header.isEmpty()) continue;

                    Cell cell = row.getCell(c);
                    String value = "";
                    if (cell != null) {
                        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                            value = cell.getDateCellValue().toInstant()
                                    .atZone(ZoneId.systemDefault()).toLocalDate().format(dtf);
                        } else {
                            value = dataFormatter.formatCellValue(cell).trim();
                        }
                    }

                    rowMap.put(header, value);
                    if (!value.isEmpty()) {
                        hasData = true;
                    }
                }

                if (hasData) {
                    rows.add(rowMap);
                }
            }
        }

        return rows;
    }

    /**
     * Basic validation: currently just filters fully empty rows.
     * Subclasses or future enhancements can add field-level validation.
     */
    List<ImportValidationError> validate(List<Map<String, String>> rows) {
        List<ImportValidationError> errors = new ArrayList<>();
        // Currently no strict validation beyond empty-row filtering (done in parseExcel).
        // Extension point for field-level validation.
        return errors;
    }

    /**
     * Resolve Excel headers to field codes.
     * Headers that match a field code directly are kept as-is.
     * Headers that match a field's displayName are mapped to the corresponding code.
     * Headers prefixed with "* " (required field marker from template) are stripped before matching.
     * Unmatched headers are kept as-is (will be passed through).
     *
     * @param headers   column headers from the Excel file
     * @param fieldDefs field definitions from the model
     * @return mapping from original header to resolved field code
     */
    /** Auto-generated fields excluded from import templates. */
    private static final Set<String> TEMPLATE_EXCLUDED_FIELDS = SystemFieldConstants.ALL_INFRASTRUCTURE;

    /**
     * Generate an import template XLSX for the given model.
     * <p>
     * Headers use displayName (falling back to field code).
     * Required fields are prefixed with "* " and highlighted with a yellow background.
     * Auto-generated fields (id, pid, timestamps, tenant) are excluded.
     *
     * @param modelCode the target model code
     * @return path to the generated temp file
     */
    public Path generateImportTemplate(String modelCode) throws IOException {
        return generateImportTemplate(modelCode, "insert");
    }

    public Path generateImportTemplate(String modelCode, String mode) throws IOException {
        ExcelImportPolicy policy = policyResolver.requireEnabled(modelCode);
        String normalizedMode = mode == null ? "insert" : mode.toLowerCase(Locale.ROOT);
        Set<String> permittedFields = "update".equals(normalizedMode)
                ? new LinkedHashSet<>(policy.getUpdateFields())
                : new LinkedHashSet<>(policy.getCreateFields());
        if ("update".equals(normalizedMode)) {
            permittedFields.addAll(policy.getUpdateKeys());
        }
        List<FieldDefinition> allFields = metaModelService.getModelFields(modelCode);

        // Filter out auto-generated, virtual, and primary key fields
        List<FieldDefinition> importableFields = new ArrayList<>();
        for (FieldDefinition fd : allFields) {
            if (TEMPLATE_EXCLUDED_FIELDS.contains(fd.getCode())) continue;
            if (fd.isPrimaryKey()) continue;
            if (fd.isComputedReadonly()) continue;
            if (!permittedFields.contains(fd.getCode())) continue;
            importableFields.add(fd);
        }

        Path tempFile = Files.createTempFile("import-template-" + modelCode, ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Import");

            // Fonts
            var headerFont = workbook.createFont();
            headerFont.setFontName("Arial Unicode MS");
            headerFont.setFontHeightInPoints((short) 11);
            headerFont.setBold(true);

            // Normal header style (gray background)
            var normalStyle = workbook.createCellStyle();
            normalStyle.setFont(headerFont);
            normalStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            normalStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            // Required header style (yellow background)
            var requiredStyle = workbook.createCellStyle();
            requiredStyle.setFont(headerFont);
            requiredStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
            requiredStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            // Write header row
            var headerRow = sheet.createRow(0);
            var commentDrawing = sheet.createDrawingPatriarch();
            List<Map.Entry<FieldDefinition, String>> referenceHints = new ArrayList<>();
            for (int i = 0; i < importableFields.size(); i++) {
                FieldDefinition fd = importableFields.get(i);
                var cell = headerRow.createCell(i);

                String label = (fd.getDisplayName() != null && !fd.getDisplayName().isBlank())
                        ? fd.getDisplayName() : fd.getCode();
                boolean required = fd.isRequired()
                        && !("insert".equals(normalizedMode)
                        && policy.getCreateAutoSetFields().contains(fd.getCode()));
                if (required) {
                    label = "* " + label;
                }
                cell.setCellValue(label);
                cell.setCellStyle(required ? requiredStyle : normalStyle);

                String referenceHint = referenceResolver.importHint(fd);
                if (referenceHint != null) {
                    var anchor = workbook.getCreationHelper().createClientAnchor();
                    anchor.setCol1(i);
                    anchor.setCol2(Math.min(i + 4, importableFields.size()));
                    anchor.setRow1(0);
                    anchor.setRow2(5);
                    var comment = commentDrawing.createCellComment(anchor);
                    comment.setString(workbook.getCreationHelper().createRichTextString(referenceHint));
                    comment.setAuthor("AuraBoot");
                    cell.setCellComment(comment);
                    referenceHints.add(Map.entry(fd, referenceHint));
                }
            }

            // Auto-size columns with minimum width
            for (int i = 0; i < importableFields.size(); i++) {
                sheet.autoSizeColumn(i);
                if (sheet.getColumnWidth(i) < 4000) {
                    sheet.setColumnWidth(i, 4000);
                }
            }

            if (!referenceHints.isEmpty()) {
                var instructions = workbook.createSheet("填写说明");
                var instructionHeader = instructions.createRow(0);
                instructionHeader.createCell(0).setCellValue("字段");
                instructionHeader.createCell(1).setCellValue("填写规则");
                instructionHeader.getCell(0).setCellStyle(normalStyle);
                instructionHeader.getCell(1).setCellStyle(normalStyle);
                for (int i = 0; i < referenceHints.size(); i++) {
                    var hintRow = instructions.createRow(i + 1);
                    FieldDefinition field = referenceHints.get(i).getKey();
                    hintRow.createCell(0).setCellValue(
                            field.getDisplayName() == null || field.getDisplayName().isBlank()
                                    ? field.getCode() : field.getDisplayName());
                    hintRow.createCell(1).setCellValue(referenceHints.get(i).getValue());
                }
                instructions.setColumnWidth(0, 6000);
                instructions.setColumnWidth(1, 24000);
                instructions.createFreezePane(0, 1);
            }

            try (OutputStream os = Files.newOutputStream(tempFile)) {
                workbook.write(os);
            }
        }

        log.info("Generated import template for model {} with {} fields", modelCode, importableFields.size());
        return tempFile;
    }

    // ==================== Chain Import ====================

    /**
     * Chain import: import parent records from Sheet1, then child records from Sheet2
     * with automatic foreign key resolution.
     *
     * @param parentModelCode parent model code (Sheet1)
     * @param childModelCode  child model code (Sheet2)
     * @param parentKeyField  unique field on parent used to match child FK values
     * @param childFkField    field on child that references the parent
     * @param excelStream     multi-sheet .xlsx file
     * @return combined import result
     */
    public ExcelImportResult chainImport(String parentModelCode, String childModelCode,
                                          String parentKeyField, String childFkField,
                                          InputStream excelStream) throws IOException {
        List<ImportValidationError> errors = new ArrayList<>();
        int parentSuccess = 0;
        int childSuccess = 0;
        int parentErrors = 0;
        int childErrors = 0;

        try (Workbook workbook = new XSSFWorkbook(excelStream)) {
            if (workbook.getNumberOfSheets() < 2) {
                return ExcelImportResult.withErrors(
                        List.of(new ImportValidationError(0, null,
                                "Chain import requires at least 2 sheets (parent + child)")), 0);
            }

            // 1. Parse parent rows from Sheet1
            Sheet parentSheet = workbook.getSheetAt(0);
            List<Map<String, String>> parentRawRows = parseSheet(parentSheet, "yyyy-MM-dd");
            if (parentRawRows.isEmpty()) {
                return ExcelImportResult.withErrors(
                        List.of(new ImportValidationError(0, null, "Sheet1 (parent) has no data rows")), 0);
            }

            // Resolve parent headers
            List<FieldDefinition> parentFields = metaModelService.getModelFields(parentModelCode);
            Map<String, String> parentHeaderMapping = resolveHeaderMapping(
                    new ArrayList<>(parentRawRows.get(0).keySet()), parentFields);
            List<Map<String, String>> parentMapped = remapRows(parentRawRows, parentHeaderMapping);

            // 2. Import parent rows and collect generated IDs keyed by parentKeyField value
            Map<String, String> parentKeyToId = new LinkedHashMap<>();
            for (int i = 0; i < parentMapped.size(); i++) {
                try {
                    Map<String, Object> rowData = new HashMap<>(parentMapped.get(i));
                    String keyValue = parentMapped.get(i).get(parentKeyField);
                    Map<String, Object> created = dynamicDataService.create(parentModelCode, rowData);
                    if (keyValue != null && created != null) {
                        Object pid = created.get("pid");
                        if (pid == null) pid = created.get("id");
                        if (pid != null) {
                            parentKeyToId.put(keyValue, pid.toString());
                        }
                    }
                    parentSuccess++;
                } catch (Exception e) {
                    parentErrors++;
                    errors.add(new ImportValidationError(i + 2, null,
                            "[Parent] " + e.getMessage()));
                }
            }

            // 3. Parse child rows from Sheet2
            Sheet childSheet = workbook.getSheetAt(1);
            List<Map<String, String>> childRawRows = parseSheet(childSheet, "yyyy-MM-dd");
            if (childRawRows.isEmpty()) {
                return ExcelImportResult.builder()
                        .totalRows(parentSuccess + parentErrors)
                        .successCount(parentSuccess).errorCount(parentErrors)
                        .createdCount(parentSuccess)
                        .errors(errors).hasErrors(parentErrors > 0).build();
            }

            // Resolve child headers
            List<FieldDefinition> childFields = metaModelService.getModelFields(childModelCode);
            Map<String, String> childHeaderMapping = resolveHeaderMapping(
                    new ArrayList<>(childRawRows.get(0).keySet()), childFields);
            List<Map<String, String>> childMapped = remapRows(childRawRows, childHeaderMapping);

            // 4. Import child rows with resolved FK
            for (int i = 0; i < childMapped.size(); i++) {
                try {
                    Map<String, Object> rowData = new HashMap<>(childMapped.get(i));
                    // Resolve FK: the child's FK field value should match a parent key value
                    Object fkValue = rowData.get(childFkField);
                    if (fkValue != null && parentKeyToId.containsKey(fkValue.toString())) {
                        rowData.put(childFkField, parentKeyToId.get(fkValue.toString()));
                    }
                    dynamicDataService.create(childModelCode, rowData);
                    childSuccess++;
                } catch (Exception e) {
                    childErrors++;
                    errors.add(new ImportValidationError(i + 2, null,
                            "[Child] " + e.getMessage()));
                }
            }
        }

        int totalSuccess = parentSuccess + childSuccess;
        int totalErrors = parentErrors + childErrors;

        return ExcelImportResult.builder()
                .totalRows(totalSuccess + totalErrors)
                .successCount(totalSuccess).errorCount(totalErrors)
                .createdCount(totalSuccess)
                .errors(errors).hasErrors(totalErrors > 0).build();
    }

    /**
     * Parse a specific sheet into row maps.
     */
    private List<Map<String, String>> parseSheet(Sheet sheet, String dateFormat) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (sheet == null || sheet.getPhysicalNumberOfRows() < 2) {
            return rows;
        }

        DataFormatter dataFormatter = new DataFormatter();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(dateFormat != null ? dateFormat : "yyyy-MM-dd");

        Row headerRow = sheet.getRow(0);
        if (headerRow == null) return rows;

        List<String> headers = new ArrayList<>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            headers.add(cell != null ? dataFormatter.formatCellValue(cell).trim() : "");
        }

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            Map<String, String> rowMap = new LinkedHashMap<>();
            boolean hasData = false;

            for (int c = 0; c < headers.size(); c++) {
                String header = headers.get(c);
                if (header.isEmpty()) continue;

                Cell cell = row.getCell(c);
                String value = "";
                if (cell != null) {
                    if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                        value = cell.getDateCellValue().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDate().format(dtf);
                    } else {
                        value = dataFormatter.formatCellValue(cell).trim();
                    }
                }

                rowMap.put(header, value);
                if (!value.isEmpty()) {
                    hasData = true;
                }
            }

            if (hasData) {
                rows.add(rowMap);
            }
        }

        return rows;
    }

    /**
     * Remap row keys using a header mapping.
     */
    private List<Map<String, String>> remapRows(List<Map<String, String>> rows, Map<String, String> headerMapping) {
        List<Map<String, String>> mapped = new ArrayList<>();
        for (Map<String, String> row : rows) {
            Map<String, String> m = new LinkedHashMap<>();
            for (var entry : row.entrySet()) {
                String key = headerMapping.getOrDefault(entry.getKey(), entry.getKey());
                m.put(key, entry.getValue());
            }
            mapped.add(m);
        }
        return mapped;
    }

    // ==================== SSE Progress ====================

    /**
     * Subscribe to SSE progress events for an import task.
     *
     * @param taskId the async import task ID
     * @return SseEmitter that streams progress events
     */
    public SseEmitter subscribeProgress(String taskId) {
        return subscribeProgress(taskId, getImportStatus(taskId));
    }

    public SseEmitter subscribeProgress(String taskId, AsyncImportStatus initialStatus) {
        if (initialStatus == null) {
            throw new BusinessException("Import task not found: " + taskId);
        }
        SseEmitter emitter = new SseEmitter(300_000L); // 5-minute timeout

        importEmitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // Cleanup on close/error/timeout
        emitter.onCompletion(() -> removeEmitter(taskId, emitter));
        emitter.onTimeout(() -> removeEmitter(taskId, emitter));
        emitter.onError(e -> removeEmitter(taskId, emitter));

        // Send initial status if task already exists
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(Map.of(
                            "taskId", taskId,
                            "status", initialStatus.getStatus(),
                            "processed", initialStatus.getProcessedRows(),
                            "total", initialStatus.getTotalRows(),
                            "errors", initialStatus.getResult() == null
                                    ? 0 : initialStatus.getResult().getErrorCount()
                    )));
            // A terminal task restored from the database has no worker left to close this
            // subscription. Complete it after the initial event instead of leaking for 5 minutes.
            if (!StatusConstants.RUNNING.equalsIgnoreCase(initialStatus.getStatus())) {
                emitter.complete();
            }
        } catch (IOException e) {
            log.debug("Failed to send initial SSE event for task {}: {}", taskId, e.getMessage());
            removeEmitter(taskId, emitter);
        }

        return emitter;
    }

    /**
     * Emit a progress event to all SSE subscribers for a task.
     */
    private void emitProgress(String taskId, int processed, int total, int errors, String status) {
        CopyOnWriteArrayList<SseEmitter> emitters = importEmitters.get(taskId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        Map<String, Object> event = Map.of(
                "taskId", taskId,
                "processed", processed,
                "total", total,
                "errors", errors,
                "status", status
        );

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(event));
            } catch (IOException e) {
                log.debug("Failed to emit progress for task {}, removing emitter", taskId);
                removeEmitter(taskId, emitter);
            }
        }
    }

    /**
     * Remove a specific emitter from a task's subscriber list.
     */
    private void removeEmitter(String taskId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = importEmitters.get(taskId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                importEmitters.remove(taskId);
            }
        }
    }

    /**
     * Close all emitters for a task (called when import completes or fails).
     */
    private void closeEmitters(String taskId) {
        CopyOnWriteArrayList<SseEmitter> emitters = importEmitters.remove(taskId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    // ==================== Import Job persistence ====================

    private void updateImportJob(Long jobId, String status, ExcelImportResult result) {
        ImportJob job = importJobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException("Import task disappeared before completion: " + jobId);
        }
        job.setStatus(status);
        job.setTotalRows(result.getTotalRows());
        job.setProcessedRows(result.getSuccessCount() + result.getErrorCount());
        job.setSuccessRows(result.getSuccessCount());
        job.setErrorRows(result.getErrorCount());
        job.setErrorDetails(serializeErrors(result.getErrors()));
        // TODO: [timezone-unification] Change to Instant once ImportJob entity fields are migrated.
        LocalDateTime now = utcNow();
        job.setCompletedAt(now);
        job.setUpdatedAt(now);
        if (importJobMapper.updateById(job) != 1) {
            throw new BusinessException("Failed to persist import task completion: " + jobId);
        }
    }

    private void updateImportJobStatus(Long jobId, String status) {
        try {
            ImportJob job = importJobMapper.selectById(jobId);
            if (job != null) {
                job.setStatus(status);
                // TODO: [timezone-unification] Change to Instant once ImportJob entity fields are migrated.
                LocalDateTime now = utcNow();
                job.setCompletedAt(now);
                job.setUpdatedAt(now);
                importJobMapper.updateById(job);
            }
        } catch (Exception e) {
            log.warn("Failed to update import job status {}: {}", jobId, e.getMessage());
        }
    }

    private String serializeErrors(List<ImportValidationError> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(
                    errors.stream().limit(MAX_PERSISTED_ROW_ERRORS).toList());
        } catch (JsonProcessingException serializationError) {
            log.error("Failed to serialize durable import row errors", serializationError);
            return null;
        }
    }

    private List<ImportValidationError> readPersistedErrors(ImportJob job) {
        if (job.getErrorDetails() == null || job.getErrorDetails().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(job.getErrorDetails(), IMPORT_ERRORS_TYPE);
        } catch (JsonProcessingException parseError) {
            log.error("Failed to restore durable row errors for import task {}", job.getPid(),
                    parseError);
            return List.of();
        }
    }

    private void updateImportJobTotalRows(Long jobId, int totalRows) {
        try {
            ImportJob job = importJobMapper.selectById(jobId);
            if (job != null) {
                job.setTotalRows(totalRows);
                // TODO: [timezone-unification] Change to Instant once ImportJob entity fields are migrated.
                job.setUpdatedAt(utcNow());
                importJobMapper.updateById(job);
            }
        } catch (Exception e) {
            log.warn("Failed to update import job total rows {}: {}", jobId, e.getMessage());
        }
    }

    private void updateImportJobProgress(Long jobId, int processed, int success, int errors) {
        try {
            ImportJob job = importJobMapper.selectById(jobId);
            if (job != null) {
                job.setProcessedRows(processed);
                job.setSuccessRows(success);
                job.setErrorRows(errors);
                // TODO: [timezone-unification] Change to Instant once ImportJob entity fields are migrated.
                job.setUpdatedAt(utcNow());
                importJobMapper.updateById(job);
            }
        } catch (Exception e) {
            log.warn("Failed to update import job progress {}: {}", jobId, e.getMessage());
        }
    }

    // ==================== Header Mapping ====================

    static Map<String, String> resolveHeaderMapping(List<String> headers, List<FieldDefinition> fieldDefs) {
        Map<String, String> displayNameToCode = new HashMap<>();
        Set<String> fieldCodes = new HashSet<>();
        for (FieldDefinition fd : fieldDefs) {
            fieldCodes.add(fd.getCode());
            if (fd.getDisplayName() != null && !fd.getDisplayName().isBlank()) {
                displayNameToCode.put(fd.getDisplayName(), fd.getCode());
            }
        }

        Map<String, String> mapping = new LinkedHashMap<>();
        for (String header : headers) {
            if (header == null || header.isBlank()) continue;
            if (fieldCodes.contains(header)) {
                mapping.put(header, header);
            } else if (displayNameToCode.containsKey(header)) {
                mapping.put(header, displayNameToCode.get(header));
            } else {
                // Check with "* " prefix stripped (required field marker from template)
                String stripped = header.startsWith("* ") ? header.substring(2) : header;
                if (fieldCodes.contains(stripped)) {
                    mapping.put(header, stripped);
                } else if (displayNameToCode.containsKey(stripped)) {
                    mapping.put(header, displayNameToCode.get(stripped));
                } else {
                    mapping.put(header, header); // pass through unmatched
                }
            }
        }
        return mapping;
    }
}
