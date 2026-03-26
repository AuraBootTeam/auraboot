package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.service.impl.ExportTaskService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Named Query Controller
 * RESTful API for managing named queries.
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/named-queries")
@RequiredArgsConstructor
@Validated
public class NamedQueryController {

    private final NamedQueryService namedQueryService;
    private final ExportTaskService exportTaskService;

    // ==================== CRUD ====================

    @PostMapping
    @RequirePermission(MetaPermission.QUERY_MANAGE)
    public ApiResponse<NamedQueryDTO> create(@Valid @RequestBody NamedQueryCreateRequest request) {
        NamedQueryDTO result = namedQueryService.create(request);
        return ApiResponse.success(result);
    }

    @PutMapping("/{pid}")
    @RequirePermission(MetaPermission.QUERY_MANAGE)
    public ApiResponse<NamedQueryDTO> update(
            @PathVariable String pid,
            @Valid @RequestBody NamedQueryUpdateRequest request) {
        NamedQueryDTO result = namedQueryService.update(pid, request);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{pid}")
    @RequirePermission(MetaPermission.QUERY_MANAGE)
    public ApiResponse<Void> delete(@PathVariable String pid) {
        namedQueryService.delete(pid);
        return ApiResponse.success();
    }

    @GetMapping("/{pid}")
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<NamedQueryDTO> findByPid(@PathVariable String pid) {
        NamedQueryDTO result = namedQueryService.findByPid(pid);
        return ApiResponse.success(result);
    }

    @GetMapping("/by-code/{code}")
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<NamedQueryDTO> findByCode(@PathVariable String code) {
        NamedQueryDTO result = namedQueryService.findByCode(code);
        return ApiResponse.success(result);
    }

    // ==================== List ====================

    @GetMapping
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<PaginationResult<NamedQueryDTO>> list(@Valid NamedQueryQueryRequest request) {
        PaginationResult<NamedQueryDTO> result = namedQueryService.list(request);
        return ApiResponse.success(result);
    }

    @GetMapping("/enabled")
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<List<NamedQueryDTO>> findEnabled() {
        List<NamedQueryDTO> result = namedQueryService.findEnabled();
        return ApiResponse.success(result);
    }

    // ==================== Status ====================

    @PutMapping("/{pid}/status")
    @RequirePermission(MetaPermission.QUERY_MANAGE)
    public ApiResponse<NamedQueryDTO> updateStatus(
            @PathVariable String pid,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        NamedQueryDTO result = namedQueryService.updateStatus(pid, status);
        return ApiResponse.success(result);
    }

    @PostMapping("/batch-status")
    @RequirePermission(MetaPermission.QUERY_MANAGE)
    public ApiResponse<NamedQueryBatchResult> batchUpdateStatus(
            @Valid @RequestBody NamedQueryBatchStatusRequest request) {
        NamedQueryBatchResult result = namedQueryService.batchUpdateStatus(request);
        return ApiResponse.success(result);
    }

    // ==================== Field management ====================

    @GetMapping("/{code}/fields")
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<List<NamedQueryFieldDTO>> getFields(@PathVariable String code) {
        List<NamedQueryFieldDTO> result = namedQueryService.getFields(code);
        return ApiResponse.success(result);
    }

    @PostMapping("/{code}/fields")
    @RequirePermission(MetaPermission.QUERY_MANAGE)
    public ApiResponse<NamedQueryFieldDTO> addField(
            @PathVariable String code,
            @Valid @RequestBody NamedQueryFieldRequest request) {
        NamedQueryFieldDTO result = namedQueryService.addField(code, request);
        return ApiResponse.success(result);
    }

    @PutMapping("/{code}/fields/{fieldCode}")
    @RequirePermission(MetaPermission.QUERY_MANAGE)
    public ApiResponse<NamedQueryFieldDTO> updateField(
            @PathVariable String code,
            @PathVariable String fieldCode,
            @Valid @RequestBody NamedQueryFieldRequest request) {
        NamedQueryFieldDTO result = namedQueryService.updateField(code, fieldCode, request);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{code}/fields/{fieldCode}")
    @RequirePermission(MetaPermission.QUERY_MANAGE)
    public ApiResponse<Void> deleteField(
            @PathVariable String code,
            @PathVariable String fieldCode) {
        namedQueryService.deleteField(code, fieldCode);
        return ApiResponse.success();
    }

    @PostMapping("/{code}/fields/batch")
    @RequirePermission(MetaPermission.QUERY_MANAGE)
    public ApiResponse<NamedQueryFieldBatchResult> batchSaveFields(
            @PathVariable String code,
            @Valid @RequestBody NamedQueryFieldBatchRequest request) {
        NamedQueryFieldBatchResult result = namedQueryService.batchSaveFields(code, request);
        return ApiResponse.success(result);
    }

    // ==================== Param Schema ====================

    @GetMapping("/{code}/param-schema")
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<List<NamedQueryFieldDTO>> getParamSchema(@PathVariable String code) {
        List<NamedQueryFieldDTO> fields = namedQueryService.getFields(code);
        // Filter to searchable fields only and sort by sort_order
        List<NamedQueryFieldDTO> schema = fields.stream()
                .filter(f -> Boolean.TRUE.equals(f.getSearchable()))
                .sorted(java.util.Comparator.comparingInt(f -> f.getSortOrder() != null ? f.getSortOrder() : 0))
                .collect(java.util.stream.Collectors.toList());
        return ApiResponse.success(schema);
    }

    // ==================== Version management ====================

    @GetMapping("/{code}/versions")
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<List<NamedQueryVersionDTO>> getVersions(@PathVariable String code) {
        List<NamedQueryVersionDTO> result = namedQueryService.getVersions(code);
        return ApiResponse.success(result);
    }

    @GetMapping("/{code}/versions/{versionNo}")
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<NamedQueryVersionDTO> getVersion(
            @PathVariable String code,
            @PathVariable int versionNo) {
        NamedQueryVersionDTO result = namedQueryService.getVersion(code, versionNo);
        return ApiResponse.success(result);
    }

    // ==================== Execution and testing ====================

    @PostMapping("/{pid}/test")
    @RequirePermission(MetaPermission.QUERY_MANAGE)
    public ApiResponse<NamedQueryTestResult> testQuery(
            @PathVariable String pid,
            @Valid @RequestBody NamedQueryTestRequest request) {
        NamedQueryTestResult result = namedQueryService.testQuery(pid, request);
        return ApiResponse.success(result);
    }

    @PostMapping("/{code}/execute")
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<PaginationResult<Map<String, Object>>> executeQuery(
            @PathVariable String code,
            @Valid @RequestBody NamedQueryTestRequest request) {
        PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(code, request);
        return ApiResponse.success(result);
    }

    // ==================== Data Export ====================

    @PostMapping("/{code}/export-data")
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<Map<String, Object>> exportData(
            @PathVariable String code,
            @Valid @RequestBody NamedQueryDataExportRequest request) {
        log.info("Export named query data: {}", code);

        ExportResult result = namedQueryService.exportData(code, request);

        if (!result.getSuccess()) {
            return ApiResponse.error(result.getErrorMessage() != null ? result.getErrorMessage() : "Export failed");
        }

        String downloadUrl = "/api/meta/named-queries/" + code + "/download?file=" +
                java.net.URLEncoder.encode(result.getFilePath(), java.nio.charset.StandardCharsets.UTF_8);

        Map<String, Object> response = Map.of(
                "success", true,
                "downloadUrl", downloadUrl,
                "recordCount", result.getRecordCount() != null ? result.getRecordCount() : 0L,
                "fileSize", result.getFileSize() != null ? result.getFileSize() : 0L,
                "format", result.getFormat() != null ? result.getFormat() : "excel"
        );

        return ApiResponse.success(response);
    }

    @GetMapping("/{code}/download")
    @RequirePermission(MetaPermission.QUERY_READ)
    public void downloadExport(
            @PathVariable String code,
            @RequestParam String file,
            HttpServletResponse response) throws java.io.IOException {
        log.info("Download named query export: code={}, file={}", code, file);

        // Security: validate file path is within temp directory to prevent path traversal
        java.nio.file.Path tempDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"));
        java.nio.file.Path filePath = java.nio.file.Paths.get(file).normalize().toAbsolutePath();
        if (!filePath.startsWith(tempDir.normalize().toAbsolutePath())) {
            log.warn("Path traversal attempt blocked: {}", file);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied");
            return;
        }
        if (!java.nio.file.Files.exists(filePath)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found");
            return;
        }

        String extension = file.substring(file.lastIndexOf('.'));
        String contentType;
        switch (extension) {
            case ".xlsx":
                contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                break;
            case ".csv":
                contentType = "text/csv; charset=UTF-8";
                break;
            case ".json":
                contentType = "application/json; charset=UTF-8";
                break;
            default:
                contentType = "application/octet-stream";
        }

        String fileName = code + "_export" + extension;
        String encodedFileName = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");

        long fileSize = java.nio.file.Files.size(filePath);
        response.setContentType(contentType);
        response.setContentLengthLong(fileSize);
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encodedFileName);
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");

        try (java.io.InputStream is = java.nio.file.Files.newInputStream(filePath);
             java.io.OutputStream os = response.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();
        }

        // Delete temp file after download
        try {
            java.nio.file.Files.deleteIfExists(filePath);
        } catch (Exception e) {
            log.warn("Failed to delete temp export file: {}", file);
        }
    }

    // ==================== Async Export ====================

    @PostMapping("/{code}/export-async")
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<ExportTaskDTO> submitAsyncExport(
            @PathVariable String code,
            @Valid @RequestBody NamedQueryDataExportRequest request) {
        Long tenantId = com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId();
        Long userId = com.auraboot.framework.application.tenant.MetaContext.getCurrentUserId();
        ExportTaskDTO result = exportTaskService.submitExport(code, request, tenantId, userId);
        return ApiResponse.success(result);
    }

    @GetMapping("/export-tasks/{taskPid}")
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<ExportTaskDTO> getExportTaskStatus(@PathVariable String taskPid) {
        ExportTaskDTO result = exportTaskService.getTaskStatus(taskPid);
        return ApiResponse.success(result);
    }

    @GetMapping("/export-tasks/{taskPid}/download")
    @RequirePermission(MetaPermission.QUERY_READ)
    public void downloadAsyncExport(
            @PathVariable String taskPid,
            HttpServletResponse response) throws java.io.IOException {
        ExportTaskDTO task = exportTaskService.getTaskStatus(taskPid);
        if (!StatusConstants.COMPLETED.equals(task.getStatus())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Export not ready");
            return;
        }

        String fileKey = exportTaskService.getFileKey(taskPid);
        if (fileKey == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found");
            return;
        }

        java.nio.file.Path filePath = java.nio.file.Paths.get(fileKey);
        if (!java.nio.file.Files.exists(filePath)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "File expired or deleted");
            return;
        }

        String extension = fileKey.substring(fileKey.lastIndexOf('.'));
        String contentType = switch (extension) {
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".csv" -> "text/csv; charset=UTF-8";
            case ".json" -> "application/json; charset=UTF-8";
            default -> "application/octet-stream";
        };

        String fileName = task.getQueryCode() + "_export" + extension;
        response.setContentType(contentType);
        response.setContentLengthLong(java.nio.file.Files.size(filePath));
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");

        try (java.io.InputStream is = java.nio.file.Files.newInputStream(filePath);
             java.io.OutputStream os = response.getOutputStream()) {
            is.transferTo(os);
        }
    }

    // ==================== Validation ====================

    @PostMapping("/validate")
    @RequirePermission(MetaPermission.QUERY_READ)
    public ApiResponse<NamedQueryValidationResult> validate(
            @Valid @RequestBody NamedQueryValidationRequest request) {
        NamedQueryValidationResult result = namedQueryService.validate(request);
        return ApiResponse.success(result);
    }
}
