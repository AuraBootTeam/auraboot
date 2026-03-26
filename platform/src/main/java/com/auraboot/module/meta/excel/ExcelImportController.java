package com.auraboot.module.meta.excel;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
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
import java.util.Map;

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

    /**
     * Download an import template for the specified model.
     * Template includes displayName headers with required field markers.
     */
    @GetMapping("/template/{modelCode}")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ResponseEntity<Resource> downloadTemplate(@PathVariable String modelCode) {
        try {
            Path templatePath = importService.generateImportTemplate(modelCode);
            String fileName = URLEncoder.encode(modelCode + "-import-template.xlsx", StandardCharsets.UTF_8);
            Resource resource = new FileSystemResource(templatePath.toFile());

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(resource);
        } catch (IOException e) {
            log.error("Failed to generate import template for model {}: {}", modelCode, e.getMessage());
            return ResponseEntity.internalServerError().build();
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
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<ExcelImportResult> importExcel(
            @PathVariable String modelCode,
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "false") boolean skipErrors,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(required = false) String upsertKey) {

        ImportOptions options = new ImportOptions();
        options.setSkipErrors(skipErrors);
        options.setDryRun(dryRun);
        options.setUpsertKey(upsertKey);

        try {
            // Check row count — if > ASYNC_THRESHOLD, run asynchronously
            byte[] fileBytes = file.getInputStream().readAllBytes();
            int rowCount = importService.countRows(new java.io.ByteArrayInputStream(fileBytes));

            if (rowCount > ExcelImportService.ASYNC_THRESHOLD) {
                String taskId = importService.importExcelAsync(modelCode,
                        new java.io.ByteArrayInputStream(fileBytes), options);
                ExcelImportResult asyncResult = ExcelImportResult.builder()
                        .totalRows(rowCount).taskId(taskId).build();
                return ApiResponse.success("Import started asynchronously", asyncResult);
            }

            ExcelImportResult result = importService.importExcel(
                    modelCode, new java.io.ByteArrayInputStream(fileBytes), options);
            return ApiResponse.success(result);
        } catch (IOException e) {
            log.error("Failed to read uploaded Excel file: {}", e.getMessage());
            return ApiResponse.error("Failed to read uploaded file: " + e.getMessage());
        }
    }

    /**
     * Poll async import task status.
     */
    @GetMapping("/import-status/{taskId}")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<ExcelImportService.AsyncImportStatus> getImportStatus(
            @PathVariable String taskId) {
        ExcelImportService.AsyncImportStatus status = importService.getImportStatus(taskId);
        if (status == null) {
            return ApiResponse.error("Task not found: " + taskId);
        }
        return ApiResponse.success(status);
    }

    /**
     * Validate an Excel file against the model's field definitions without importing.
     * Returns a detailed validation report with errors and warnings.
     */
    @PostMapping("/validate")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<ValidationReport> validateFile(
            @RequestParam String modelCode,
            @RequestParam MultipartFile file) {
        try {
            ValidationReport report = validationEngine.validate(modelCode, file.getInputStream());
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
    @RequirePermission(MetaPermission.MODEL_MANAGE)
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
    @GetMapping(value = "/import/{taskId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(@PathVariable String taskId) {
        return importService.subscribeProgress(taskId);
    }
}
