package com.auraboot.module.meta.excel;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.i18n.util.I18nLocaleResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

/**
 * REST controller for Excel import operations.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/excel")
@RequiredArgsConstructor
public class ExcelImportController {

    private final ExcelImportService importService;
    private final ExcelValidationEngine validationEngine;
    private final ExcelImportPolicyResolver policyResolver;
    private final ExcelImportErrorReportService errorReportService;
    private final I18nLocaleResolver i18nLocaleResolver;

    private static final long MAX_IMPORT_BYTES = 10L * 1024L * 1024L;

    /**
     * Download an import template for the specified model.
     * Template includes displayName headers with required field markers.
     */
    @GetMapping("/template/{modelCode}")
    @RequirePermission("model.{modelCode}.import")
    public ResponseEntity<Resource> downloadTemplate(
            @PathVariable String modelCode,
            @RequestParam(defaultValue = "insert") String mode) {
        Path templatePath = null;
        try {
            ExcelImportPolicy policy = policyResolver.requireEnabled(modelCode);
            policyResolver.validateMode(policy, mode, "update".equalsIgnoreCase(mode)
                    ? policy.getUpdateKeys().stream().findFirst().orElse(null) : null);
            templatePath = importService.generateImportTemplate(modelCode, mode);
            String fileName = URLEncoder.encode(modelCode + "-import-template.xlsx", StandardCharsets.UTF_8);
            Resource resource = new ByteArrayResource(Files.readAllBytes(templatePath));

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(resource);
        } catch (IOException e) {
            log.error("Failed to generate import template for model {}: {}", modelCode, e.getMessage());
            return ResponseEntity.internalServerError().build();
        } finally {
            if (templatePath != null) {
                try {
                    Files.deleteIfExists(templatePath);
                } catch (IOException cleanupError) {
                    log.warn("Failed to delete generated import template {}", templatePath, cleanupError);
                }
            }
        }
    }

    /**
     * Import an Excel file into the specified model.
     *
     * @param modelCode  target model code
     * @param file       the .xlsx file
     * @param skipErrors if true, continue on row errors
     * @param dryRun     if true, validate only without persisting
     * @return import result with success/error counts
     */
    @PostMapping("/import/{modelCode}")
    @RequirePermission("model.{modelCode}.import")
    public ApiResponse<ExcelImportResult> importExcel(
            @PathVariable String modelCode,
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "false") boolean skipErrors,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "insert") String mode,
            @RequestParam(required = false) String matchKey,
            HttpServletRequest httpRequest) {

        validateUpload(file);
        ExcelImportPolicy policy = policyResolver.requireEnabled(modelCode);
        policyResolver.validateMode(policy, mode, matchKey);
        ImportOptions options = new ImportOptions();
        options.setSkipErrors(skipErrors);
        options.setDryRun(dryRun);
        options.setImportMode(mode);
        options.setMatchKey(matchKey);
        options.setReportLocale(i18nLocaleResolver.resolveLocale(httpRequest));

        try {
            // Check row count — larger files run asynchronously. The default is 1000;
            // isolated acceptance stacks may lower it to exercise the durable job path.
            byte[] fileBytes = file.getInputStream().readAllBytes();
            ValidationReport validation = validationEngine.validate(
                    modelCode, new java.io.ByteArrayInputStream(fileBytes), mode, matchKey);
            if (!validation.isValid()) {
                List<ImportValidationError> errors = validation.getErrors().stream()
                        .map(error -> new ImportValidationError(
                                error.getRowNumber(), error.getFieldCode(), error.getMessage()))
                        .toList();
                ExcelImportResult invalidResult = ExcelImportResult.withErrors(
                        errors, validation.getTotalRows());
                attachSynchronousReport(modelCode, file.getOriginalFilename(), mode,
                        fileBytes, invalidResult, options.getReportLocale(),
                        ExcelImportErrorReportService.RowSelection.ALL_ROWS);
                return ApiResponse.success("Validation failed", invalidResult);
            }
            int rowCount = validation.getTotalRows();

            if (rowCount > importService.getAsyncThreshold()) {
                String taskId = importService.importExcelAsync(modelCode,
                        new java.io.ByteArrayInputStream(fileBytes), options, file.getOriginalFilename());
                ExcelImportResult asyncResult = ExcelImportResult.builder()
                        .totalRows(rowCount).taskId(taskId).build();
                return ApiResponse.success("Import started asynchronously", asyncResult);
            }

            ExcelImportResult result = importService.importExcel(
                    modelCode, new java.io.ByteArrayInputStream(fileBytes), options);
            if (result.isHasErrors() && result.getErrors() != null && !result.getErrors().isEmpty()) {
                attachSynchronousReport(modelCode, file.getOriginalFilename(), mode,
                        fileBytes, result, options.getReportLocale(),
                        reportSelection(result, options));
            }
            return ApiResponse.success(result);
        } catch (IOException e) {
            log.error("Failed to read uploaded Excel file: {}", e.getMessage());
            return ApiResponse.error("Failed to read uploaded file: " + e.getMessage());
        }
    }

    /**
     * Poll async import task status.
     */
    @GetMapping("/import/{modelCode}/status/{taskId}")
    @RequirePermission("model.{modelCode}.import")
    public ApiResponse<ExcelImportService.AsyncImportStatus> getImportStatus(
            @PathVariable String modelCode,
            @PathVariable String taskId) {
        ExcelImportService.AsyncImportStatus status = importService.requireImportStatus(modelCode, taskId);
        return status == null
                ? ApiResponse.error("Task not found: " + taskId)
                : ApiResponse.success(status);
    }

    /** Request cooperative cancellation of a running import owned by the current user. */
    @PostMapping("/import/{modelCode}/cancel/{taskId}")
    @RequirePermission("model.{modelCode}.import")
    public ApiResponse<ExcelImportService.AsyncImportStatus> cancelImport(
            @PathVariable String modelCode,
            @PathVariable String taskId) {
        ExcelImportService.AsyncImportStatus status = importService.cancelImport(modelCode, taskId);
        return status == null
                ? ApiResponse.error("Task not found: " + taskId)
                : ApiResponse.success("Cancellation requested", status);
    }

    /**
     * Validate an Excel file against the model's field definitions without importing.
     * Returns a detailed validation report with errors and warnings.
     */
    @PostMapping("/validate/{modelCode}")
    @RequirePermission("model.{modelCode}.import")
    public ApiResponse<ValidationReport> validateFile(
            @PathVariable String modelCode,
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "insert") String mode,
            @RequestParam(required = false) String matchKey,
            HttpServletRequest httpRequest) {
        validateUpload(file);
        ExcelImportPolicy policy = policyResolver.requireEnabled(modelCode);
        policyResolver.validateMode(policy, mode, matchKey);
        try {
            byte[] fileBytes = file.getBytes();
            ValidationReport report = validationEngine.validate(
                    modelCode, new java.io.ByteArrayInputStream(fileBytes), mode, matchKey);
            if (!report.isValid()) {
                List<ImportValidationError> errors = report.getErrors().stream()
                        .map(error -> new ImportValidationError(
                                error.getRowNumber(), error.getFieldCode(), error.getMessage()))
                        .toList();
                try {
                    ExcelImportErrorReportService.ReportRegistration registration =
                            errorReportService.createReport(modelCode, file.getOriginalFilename(), mode,
                                    fileBytes, errors, report.getTotalRows(), 0,
                                    i18nLocaleResolver.resolveLocale(httpRequest),
                                    ExcelImportErrorReportService.RowSelection.ALL_ROWS);
                    report.setTaskId(registration.taskId());
                    report.setErrorReportUrl(registration.downloadUrl());
                } catch (RuntimeException reportError) {
                    // CATCH: validation remains authoritative if non-transactional report storage
                    // is unavailable. The UI can still show every row error inline.
                    log.error("Validation completed but its correction workbook could not be "
                                    + "created: model={}, file={}",
                            modelCode, file.getOriginalFilename(), reportError);
                    report.setErrorReportFailed(true);
                }
            }
            return ApiResponse.success(report);
        } catch (IOException e) {
            log.error("Failed to validate Excel file for model {}: {}", modelCode, e.getMessage());
            return ApiResponse.error("Failed to read uploaded file: " + e.getMessage());
        }
    }

    /**
     * Chain import: import parent records from Sheet1, then child records from Sheet2
     * with automatic FK resolution.
     *
     * @param parentModelCode parent model code (Sheet1)
     * @param childModelCode  child model code (Sheet2)
     * @param parentKeyField  unique field on parent used to match child FK values
     * @param childFkField    field on child that references the parent
     * @param file            multi-sheet .xlsx file
     */
    @PostMapping("/chain-import")
    @RequirePermission("meta.model.update")
    public ApiResponse<ExcelImportResult> chainImport(
            @RequestParam String parentModelCode,
            @RequestParam String childModelCode,
            @RequestParam String parentKeyField,
            @RequestParam String childFkField,
            @RequestParam MultipartFile file) {
        try {
            ExcelImportResult result = importService.chainImport(
                    parentModelCode, childModelCode, parentKeyField, childFkField,
                    file.getInputStream());
            return ApiResponse.success(result);
        } catch (IOException e) {
            log.error("Chain import failed: {}", e.getMessage());
            return ApiResponse.error("Chain import failed: " + e.getMessage());
        }
    }

    /**
     * SSE endpoint for streaming import progress of an async task.
     */
    @GetMapping(value = "/import/{modelCode}/{taskId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission("model.{modelCode}.import")
    public SseEmitter streamProgress(@PathVariable String modelCode, @PathVariable String taskId) {
        ExcelImportService.AsyncImportStatus status = importService.requireImportStatus(modelCode, taskId);
        if (status == null) {
            throw new BusinessException("Import task not found: " + taskId);
        }
        return importService.subscribeProgress(taskId, status);
    }

    /**
     * Download the failed-row correction workbook for an import owned by the current user.
     */
    @GetMapping("/import/{modelCode}/error-report/{taskId}")
    @RequirePermission("model.{modelCode}.import")
    public ResponseEntity<Resource> downloadErrorReport(
            @PathVariable String modelCode, @PathVariable String taskId) {
        ExcelImportErrorReportService.ReportDownload report =
                errorReportService.findDownload(modelCode, taskId);
        if (report == null) {
            return ResponseEntity.notFound().build();
        }
        String fileName = URLEncoder.encode(report.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + fileName)
                .contentType(MediaType.parseMediaType(
                        ExcelImportErrorReportService.XLSX_CONTENT_TYPE))
                .body(new InputStreamResource(report.content()));
    }

    private void attachSynchronousReport(String modelCode, String fileName, String mode,
                                         byte[] fileBytes, ExcelImportResult result,
                                         String locale,
                                         ExcelImportErrorReportService.RowSelection rowSelection) {
        try {
            ExcelImportErrorReportService.ReportRegistration registration =
                    errorReportService.createReport(modelCode, fileName, mode, fileBytes,
                            result.getErrors(), result.getTotalRows(), result.getSuccessCount(), locale,
                            rowSelection);

            result.setTaskId(registration.taskId());
            result.setErrorReportUrl(registration.downloadUrl());
        } catch (RuntimeException reportError) {
            // CATCH: report storage is non-transactional. Preserve truthful row counts because
            // synchronous imports may already have committed successful rows.
            log.error("Import completed with row errors but its correction workbook could not "
                    + "be created: model={}, file={}", modelCode, fileName, reportError);
            result.setErrorReportFailed(true);
        }
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

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("An .xlsx file is required");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException("Only .xlsx files are supported");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new BusinessException("Import file exceeds the 10 MB limit");
        }
    }
}
