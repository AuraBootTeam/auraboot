package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.service.PluginImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.AsyncTaskDTO;
import com.auraboot.framework.meta.dto.AsyncTaskSubmitRequest;
import com.auraboot.framework.meta.service.impl.AsyncTaskServiceImpl;
import com.auraboot.framework.plugin.service.impl.PluginImportAsyncTaskExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * REST API for plugin import operations.
 *
 * <h2>Quick Start — 3 Import Flows</h2>
 *
 * <h3>Flow 1: One-step directory import (recommended for CLI/E2E)</h3>
 * <pre>{@code
 * POST /api/plugins/import/import-directory
 * {
 *   "path": "/absolute/path/to/plugin-dir",
 *   "conflictStrategy": "overwrite",
 *   "autoPublishModels": true,
 *   "autoPublishFields": true,
 *   "autoPublishCommands": true,
 *   "autoPublishPages": true
 * }
 * }</pre>
 *
 * <h3>Flow 2: Two-step with preview (for UI)</h3>
 * <pre>{@code
 * // Step 1 — parse and preview
 * POST /api/plugins/import/parse-directory
 * { "path": "/absolute/path/to/plugin-dir" }
 * // returns { importId, resources[], conflicts[] }
 *
 * // Step 2 — execute after review
 * POST /api/plugins/import/{importId}/execute
 * { "conflictStrategy": "overwrite" }
 * }</pre>
 *
 * <h3>Flow 3: Inline manifest (for testing)</h3>
 * <pre>{@code
 * POST /api/plugins/import/execute-direct?conflictStrategy=OVERWRITE
 * { "pluginId": "...", "fields": [...], "models": [...], ... }
 * }</pre>
 *
 * <h3>Authentication</h3>
 * <p>All endpoints require {@code plugin.plugin.manage} permission.
 * Pass JWT via {@code Authorization: Bearer <token>} header.
 * When calling from Playwright, use {@code page.request} (BFF cookie auth) with
 * relative URLs {@code /api/...} — do NOT use the backend port directly.</p>
 *
 * <h3>Auto-publish behavior</h3>
 * <p>By default, all {@code autoPublish*} flags are {@code true} in Flow 1 &amp; 3.
 * Model publish triggers DYNAMIC permission creation
 * ({@code dynamic.<code>.read/create/manage}), which is required for page access.</p>
 *
 * <h3>curl example</h3>
 * <pre>{@code
 * TOKEN=$(curl -s http://localhost:6443/api/auth/login \
 *   -H 'Content-Type: application/json' \
 *   -d '{"email":"admin@example.com","password":" Test2026x"}' | jq -r .data.jwt)
 *
 * curl -X POST http://localhost:6443/api/plugins/import/import-directory \
 *   -H "Authorization: Bearer $TOKEN" \
 *   -H 'Content-Type: application/json' \
 *   -d '{"path":"/path/to/plugin","conflictStrategy":"overwrite"}'
 * }</pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/plugins/import")
@RequiredArgsConstructor
@RequirePermission("plugin.plugin.manage")
@Tag(name = "Plugin Import", description = "Plugin import and configuration management")
public class PluginImportController {

    private final PluginImportService importService;
    private final AsyncTaskServiceImpl asyncTaskService;
    private final ObjectMapper objectMapper;
    // ==================== Upload & Parse ====================

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload plugin package", description = "Upload and parse a plugin package (JSON or ZIP)")
    public ResponseEntity<ImportPreviewResult> upload(
            @Parameter(description = "Plugin package file (JSON or ZIP)")
            @RequestParam("file") MultipartFile file) {

        log.info("Uploading plugin package: {}", file.getOriginalFilename());
        ImportPreviewResult result = importService.upload(file);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/parse")
    @Operation(summary = "Parse JSON manifest", description = "Parse a plugin manifest from JSON content")
    public ResponseEntity<ImportPreviewResult> parseJson(
            @Parameter(description = "JSON manifest content")
            @RequestBody String jsonContent,
            @RequestParam(value = "sourceName", defaultValue = "inline") String sourceName) {

        log.info("Parsing inline JSON manifest");
        ImportPreviewResult result = importService.parseJson(jsonContent, sourceName);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/parse-directory")
    @Operation(summary = "Parse plugin from directory",
            description = "Parse a plugin from a directory-based structure with separate files for each resource type")
    public ResponseEntity<ImportPreviewResult> parseDirectory(
            @Parameter(description = "Absolute path to the plugin directory")
            @RequestBody Map<String, String> request) {

        String directoryPath = request.get("path");
        if (directoryPath == null || directoryPath.isBlank()) {
            ImportPreviewResult result = new ImportPreviewResult();
            result.setValid(false);
            result.addError("path is required");
            return ResponseEntity.badRequest().body(result);
        }

        // Path traversal protection: normalize and reject directory traversal
        Path normalized = Path.of(directoryPath).normalize();
        if (!normalized.isAbsolute() || normalized.toString().contains("..")) {
            ImportPreviewResult result = new ImportPreviewResult();
            result.setValid(false);
            result.addError("Invalid directory path: must be absolute and cannot contain '..'");
            return ResponseEntity.badRequest().body(result);
        }

        log.info("Parsing plugin from directory: {}", normalized);
        ImportPreviewResult result = importService.parseDirectory(normalized.toString());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/import-directory")
    @Operation(summary = "Import plugin from directory (async)",
            description = "Submit an async task to import a plugin from a directory. "
                    + "Returns a taskCode immediately. Poll GET /api/async-tasks/{taskCode} for progress.")
    public ResponseEntity<ApiResponse<AsyncTaskDTO>> importDirectory(
            @Parameter(description = "Directory path and import options")
            @RequestBody DirectoryImportRequest request) {

        if (request.getPath() == null || request.getPath().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("path is required"));
        }

        // Path traversal protection
        Path normalizedPath = Path.of(request.getPath()).normalize();
        if (!normalizedPath.isAbsolute() || normalizedPath.toString().contains("..")) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("Invalid directory path: must be absolute and cannot contain '..'"));
        }

        // Quick check: directory exists and has plugin.json (no parsing, fast)
        java.io.File dir = normalizedPath.toFile();
        if (!dir.isDirectory()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("Path is not a directory: " + normalizedPath));
        }
        java.io.File pluginJson = new java.io.File(dir, "plugin.json");
        if (!pluginJson.exists()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("Directory does not contain plugin.json: " + normalizedPath));
        }

        // Server-side fail-fast validation before async submission.
        // Prevents avoidable runtime task failures caused by malformed plugin resources.
        ImportPreviewResult preview = importService.parseDirectory(normalizedPath.toString());
        if (!preview.isValid()) {
            String errors = String.join("; ", preview.getErrors());
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("Invalid plugin: " + errors));
        }

        log.info("Submitting async plugin import from directory: {}", normalizedPath);

        // Build async task input params with current user context
        MetaContext ctx = MetaContext.get();
        ObjectNode inputParams = objectMapper.createObjectNode();
        inputParams.put("directoryPath", normalizedPath.toString());
        inputParams.put("conflictStrategy",
                request.getConflictStrategy() != null
                        ? request.getConflictStrategy().name() : "overwrite");
        inputParams.put("autoDeployProcesses", request.isAutoDeployProcesses());
        inputParams.put("autoPublishModels", request.isAutoPublishModels());
        inputParams.put("autoPublishFields", request.isAutoPublishFields());
        inputParams.put("autoPublishCommands", request.isAutoPublishCommands());
        inputParams.put("autoPublishPages", request.isAutoPublishPages());
        inputParams.put("tenantId", ctx.getTenantId());
        inputParams.put("userId", ctx.getUserId());
        inputParams.put("userPid", ctx.getUserPid());
        inputParams.put("username", ctx.getUsername());

        // Submit async task
        AsyncTaskSubmitRequest taskRequest = new AsyncTaskSubmitRequest();
        taskRequest.setTaskType(PluginImportAsyncTaskExecutor.TASK_TYPE);
        taskRequest.setTaskName("Import plugin from: " + normalizedPath.getFileName());
        taskRequest.setInputParams(inputParams);
        taskRequest.setMaxRetries(0); // No auto-retry for plugin imports
        taskRequest.setTimeoutSeconds(600); // 10 minutes

        AsyncTaskDTO taskDTO = asyncTaskService.submitTask(taskRequest, ctx.getTenantId(), ctx.getUserId());

        log.info("Async plugin import submitted: taskCode={}, directory={}",
                taskDTO.getTaskCode(), normalizedPath);

        return ResponseEntity.accepted().body(
                ApiResponse.success("Plugin import started asynchronously", taskDTO));
    }

    @PostMapping("/import-directory-sync")
    @Operation(summary = "Import plugin from directory (synchronous)",
            description = "Parse and execute import synchronously. Use for small plugins or E2E tests. "
                    + "For large plugins, prefer the async /import-directory endpoint.")
    public ResponseEntity<ImportExecuteResult> importDirectorySync(
            @Parameter(description = "Directory path and import options")
            @RequestBody DirectoryImportRequest request) {

        if (request.getPath() == null || request.getPath().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Path normalizedPath = Path.of(request.getPath()).normalize();
        if (!normalizedPath.isAbsolute() || normalizedPath.toString().contains("..")) {
            return ResponseEntity.badRequest().body(ImportExecuteResult.builder()
                    .success(false)
                    .errorMessage("Invalid directory path: must be absolute and cannot contain '..'")
                    .build());
        }

        log.info("Importing plugin from directory (sync): {}", normalizedPath);

        ImportPreviewResult preview = importService.parseDirectory(normalizedPath.toString());
        if (!preview.isValid()) {
            return ResponseEntity.badRequest().body(ImportExecuteResult.builder()
                    .success(false)
                    .errorMessage("Invalid plugin: " + String.join(", ", preview.getErrors()))
                    .build());
        }

        ImportRequest importRequest = ImportRequest.builder()
                .importId(preview.getImportId())
                .conflictStrategy(request.getConflictStrategy() != null
                        ? request.getConflictStrategy()
                        : ImportRequest.ConflictStrategy.OVERWRITE)
                .autoDeployProcesses(request.isAutoDeployProcesses())
                .autoPublishModels(request.isAutoPublishModels())
                .autoPublishFields(request.isAutoPublishFields())
                .autoPublishCommands(request.isAutoPublishCommands())
                .autoPublishPages(request.isAutoPublishPages())
                .build();

        ImportExecuteResult result = importService.execute(preview.getImportId(), importRequest);

        return ResponseEntity.ok(result);
    }

    /**
     * Request DTO for directory-based import.
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DirectoryImportRequest {
        private String path;
        private ImportRequest.ConflictStrategy conflictStrategy;
        private boolean autoDeployProcesses = true;
        private boolean autoPublishModels = true;
        private boolean autoPublishFields = true;
        private boolean autoPublishCommands = true;
        private boolean autoPublishPages = true;
    }

    // ==================== Preview ====================

    @GetMapping("/{importId}/preview")
    @Operation(summary = "Get import preview", description = "Get the preview result for an import")
    public ResponseEntity<ImportPreviewResult> getPreview(
            @Parameter(description = "Import ID from upload")
            @PathVariable String importId) {

        ImportPreviewResult result = importService.getPreview(importId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{importId}/preview")
    @Operation(summary = "Regenerate preview", description = "Regenerate preview with different options")
    public ResponseEntity<ImportPreviewResult> preview(
            @Parameter(description = "Import ID from upload")
            @PathVariable String importId,
            @RequestBody ImportRequest request) {

        request.setImportId(importId);
        ImportPreviewResult result = importService.preview(importId, request);
        return ResponseEntity.ok(result);
    }

    // ==================== Execute ====================

    @PostMapping("/{importId}/execute")
    @Operation(summary = "Execute import", description = "Execute the import after preview")
    public ResponseEntity<ImportExecuteResult> execute(
            @Parameter(description = "Import ID from upload")
            @PathVariable String importId,
            @RequestBody(required = false) ImportRequest request) {

        if (request == null) {
            request = new ImportRequest();
        }
        request.applyDefaults();
        request.setImportId(importId);

        log.info("Executing plugin import: {}", importId);
        ImportExecuteResult result = importService.execute(importId, request);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/execute-direct")
    @Operation(summary = "Execute direct import", description = "Import manifest directly without preview. "
            + "Pass dryRun=true to validate and check conflicts without persisting.")
    public ResponseEntity<?> executeDirect(
            @RequestBody PluginManifestExtended manifest,
            @RequestParam(value = "conflictStrategy", defaultValue = "error")
            ImportRequest.ConflictStrategy conflictStrategy,
            @RequestParam(value = "autoDeployProcesses", defaultValue = "true") boolean autoDeployProcesses,
            @RequestParam(value = "autoPublishModels", defaultValue = "true") boolean autoPublishModels,
            @RequestParam(value = "autoPublishFields", defaultValue = "true") boolean autoPublishFields,
            @RequestParam(value = "autoPublishCommands", defaultValue = "true") boolean autoPublishCommands,
            @RequestParam(value = "autoPublishPages", defaultValue = "true") boolean autoPublishPages,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {

        // Dry-run: validate + conflict check without persisting anything
        if (dryRun) {
            log.info("Dry-run plugin import validation: {}", manifest.getPluginId());
            List<String> errors = importService.validateManifest(manifest);
            List<ImportPreviewResult.ResourceConflict> conflicts = importService.checkConflicts(manifest);
            ImportPreviewResult.DependencyAnalysis dependencies = importService.analyzeDependencies(manifest);
            return ResponseEntity.ok(Map.of(
                    "dryRun", true,
                    "valid", errors.isEmpty() && dependencies.isSatisfied(),
                    "errors", errors,
                    "conflicts", conflicts,
                    "dependencies", dependencies
            ));
        }

        ImportRequest request = ImportRequest.builder()
                .conflictStrategy(conflictStrategy)
                .autoDeployProcesses(autoDeployProcesses)
                .autoPublishModels(autoPublishModels)
                .autoPublishFields(autoPublishFields)
                .autoPublishCommands(autoPublishCommands)
                .autoPublishPages(autoPublishPages)
                .build();

        log.info("Executing direct plugin import: {}", manifest.getPluginId());
        ImportExecuteResult result = importService.executeFromManifest(manifest, request);
        return ResponseEntity.ok(result);
    }

    // ==================== Rollback ====================

    @PostMapping("/{importId}/rollback")
    @Operation(summary = "Rollback import", description = "Rollback a successful import")
    public ResponseEntity<ImportExecuteResult> rollback(
            @Parameter(description = "Import ID to rollback")
            @PathVariable String importId) {

        log.info("Rolling back plugin import: {}", importId);
        ImportExecuteResult result = importService.rollback(importId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{importId}/can-rollback")
    @Operation(summary = "Check rollback eligibility", description = "Check if an import can be rolled back")
    public ResponseEntity<Map<String, Boolean>> canRollback(
            @Parameter(description = "Import ID")
            @PathVariable String importId) {

        boolean canRollback = importService.canRollback(importId);
        return ResponseEntity.ok(Map.of("canRollback", canRollback));
    }

    // ==================== History & Status ====================

    @GetMapping("/history")
    @Operation(summary = "Get import history", description = "Get plugin import history for current tenant")
    public ResponseEntity<List<PluginImportService.ImportHistoryDTO>> getHistory(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {

        List<PluginImportService.ImportHistoryDTO> history = importService.getImportHistory(limit);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/history/plugin/{pluginId}")
    @Operation(summary = "Get plugin import history", description = "Get import history for a specific plugin")
    public ResponseEntity<List<PluginImportService.ImportHistoryDTO>> getPluginHistory(
            @Parameter(description = "Plugin ID")
            @PathVariable String pluginId) {

        List<PluginImportService.ImportHistoryDTO> history = importService.getPluginImportHistory(pluginId);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/{importId}/status")
    @Operation(summary = "Get import status", description = "Get the status of an import operation")
    public ResponseEntity<PluginImportService.ImportHistoryDTO> getStatus(
            @Parameter(description = "Import ID")
            @PathVariable String importId) {

        PluginImportService.ImportHistoryDTO status = importService.getImportStatus(importId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @PostMapping("/{importId}/cancel")
    @Operation(summary = "Cancel import", description = "Cancel an in-progress import")
    public ResponseEntity<Map<String, Boolean>> cancel(
            @Parameter(description = "Import ID")
            @PathVariable String importId) {

        log.info("Cancelling plugin import: {}", importId);
        boolean cancelled = importService.cancelImport(importId);
        return ResponseEntity.ok(Map.of("cancelled", cancelled));
    }

    // ==================== SSE Progress Stream ====================

    /**
     * Stream install progress as Server-Sent Events for a given async task code.
     *
     * <p>Polls the task every 800ms and pushes progress updates until the task
     * reaches a terminal state (SUCCESS, FAILED, CANCELLED, TIMED_OUT).</p>
     *
     * <p>Each SSE event is a JSON object:</p>
     * <pre>
     * { "taskCode": "...", "status": "running", "progress": 42,
     *   "progressMessage": "Importing resources...", "done": false }
     * </pre>
     *
     * GET /api/plugins/import/tasks/{taskCode}/progress
     */
    @GetMapping(value = "/tasks/{taskCode}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream install progress (SSE)")
    public SseEmitter streamProgress(@PathVariable String taskCode) {
        // 10-minute timeout — matches the async task timeout
        SseEmitter emitter = new SseEmitter(600_000L);

        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sse-plugin-progress-" + taskCode);
            t.setDaemon(true);
            return t;
        }).execute(() -> {
            int maxPolls = 750; // 10 min at 800ms
            try {
                for (int i = 0; i < maxPolls; i++) {
                    AsyncTaskDTO task = asyncTaskService.getTask(taskCode);
                    if (task == null) {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data("{\"error\":\"Task not found: " + taskCode + "\"}"));
                        emitter.complete();
                        return;
                    }

                    ObjectNode data = objectMapper.createObjectNode();
                    data.put("taskCode", taskCode);
                    data.put("status", task.getStatus());
                    data.put("progress", task.getProgress() != null ? task.getProgress() : 0);
                    data.put("progressMessage",
                            task.getProgressMessage() != null ? task.getProgressMessage() : "");
                    data.put("errorMessage",
                            task.getErrorMessage() != null ? task.getErrorMessage() : "");

                    boolean terminal = isTerminal(task.getStatus());
                    data.put("done", terminal);

                    // Include resource counts when done
                    if (terminal && task.getResultData() != null
                            && task.getResultData().has("resourceCounts")) {
                        data.set("resourceCounts", task.getResultData().get("resourceCounts"));
                    }

                    emitter.send(SseEmitter.event()
                            .name("progress")
                            .data(objectMapper.writeValueAsString(data)));

                    if (terminal) {
                        emitter.complete();
                        return;
                    }

                    Thread.sleep(800);
                }
                // Timeout reached — send final event
                ObjectNode timeoutData = objectMapper.createObjectNode();
                timeoutData.put("taskCode", taskCode);
                timeoutData.put("status", "timed_out");
                timeoutData.put("progress", 100);
                timeoutData.put("progressMessage", "Timed out waiting for completion.");
                timeoutData.put("done", true);
                emitter.send(SseEmitter.event().name("progress").data(objectMapper.writeValueAsString(timeoutData)));
                emitter.complete();
            } catch (IOException e) {
                // Client disconnected — not an error
                log.debug("SSE client disconnected for task {}", taskCode);
                emitter.completeWithError(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.warn("SSE progress stream error for task {}: {}", taskCode, e.getMessage());
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"error\":\"" + e.getMessage() + "\"}"));
                } catch (IOException ignored) { /* ignored */ }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private static boolean isTerminal(String status) {
        return StatusConstants.SUCCESS.equals(status) || StatusConstants.FAILED.equals(status)
                || StatusConstants.CANCELLED.equals(status) || "timed_out".equals(status);
    }

    // ==================== Validation ====================

    @PostMapping("/validate")
    @Operation(summary = "Validate manifest", description = "Validate a manifest without importing")
    public ResponseEntity<Map<String, Object>> validate(
            @RequestBody PluginManifestExtended manifest) {

        List<String> errors = importService.validateManifest(manifest);
        List<ImportPreviewResult.ResourceConflict> conflicts = importService.checkConflicts(manifest);
        ImportPreviewResult.DependencyAnalysis dependencies = importService.analyzeDependencies(manifest);

        return ResponseEntity.ok(Map.of(
                "valid", errors.isEmpty() && dependencies.isSatisfied(),
                "errors", errors,
                "conflicts", conflicts,
                "dependencies", dependencies
        ));
    }
}
