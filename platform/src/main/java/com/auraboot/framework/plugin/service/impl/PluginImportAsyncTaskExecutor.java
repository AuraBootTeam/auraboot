package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.AsyncTaskExecutor;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Async task executor for plugin imports.
 *
 * <p>Handles large plugin imports (like PCBA ERP with 126 models, 1210 fields)
 * asynchronously to avoid HTTP timeout issues.</p>
 *
 * <p>Input params:</p>
 * <pre>
 * {
 *   "directoryPath": "/absolute/path/to/plugin-dir",
 *   "conflictStrategy": "overwrite",
 *   "autoPublishModels": true,
 *   "autoPublishFields": true,
 *   "autoPublishCommands": true,
 *   "autoPublishPages": true,
 *   "autoDeployProcesses": false,
 *   "tenantId": 123456,
 *   "userId": 789,
 *   "userPid": "01ABC...",
 *   "username": "admin@auraboot.com"
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginImportAsyncTaskExecutor implements AsyncTaskExecutor {

    public static final String TASK_TYPE = "plugin_import";

    private final PluginImportService importService;
    private final ObjectMapper objectMapper;

    @Override
    public String getTaskType() {
        return TASK_TYPE;
    }

    @Override
    public AsyncTaskResult execute(JsonNode inputParams, ProgressCallback callback) {
        String directoryPath = inputParams.path("directoryPath").asText("");
        if (directoryPath.isBlank()) {
            return AsyncTaskResult.fail("Missing 'directoryPath' in input params");
        }

        Long tenantId = inputParams.path("tenantId").asLong(0);
        Long userId = inputParams.path("userId").asLong(0);
        String userPid = inputParams.path("userPid").asText(null);
        String username = inputParams.path("username").asText("system");

        if (tenantId == 0) {
            return AsyncTaskResult.fail("Missing 'tenantId' in input params");
        }

        // Set MetaContext for the async thread
        MetaContext.setContext(tenantId, userId, userPid, username);
        try {
            return doImport(inputParams, directoryPath, callback);
        } finally {
            MetaContext.clear();
        }
    }

    private AsyncTaskResult doImport(JsonNode inputParams, String directoryPath, ProgressCallback callback) {
        try {
            // Step 1: Parse directory (5%)
            callback.report(5, "Parsing plugin directory...");
            ImportPreviewResult preview = importService.parseDirectory(directoryPath);

            if (!preview.isValid()) {
                String errors = String.join(", ", preview.getErrors());
                return AsyncTaskResult.fail("Invalid plugin: " + errors);
            }

            callback.report(15, "Plugin parsed: " + preview.getPluginId() + " v" + preview.getVersion());

            // Step 2: Build import request (20%)
            callback.report(20, "Preparing import...");

            String conflictStrategy = inputParams.path("conflictStrategy").asText("overwrite");
            ImportRequest importRequest = ImportRequest.builder()
                    .importId(preview.getImportId())
                    .conflictStrategy(ImportRequest.ConflictStrategy.valueOf(conflictStrategy))
                    .autoDeployProcesses(inputParams.path("autoDeployProcesses").asBoolean(false))
                    .autoPublishModels(inputParams.path("autoPublishModels").asBoolean(true))
                    .autoPublishFields(inputParams.path("autoPublishFields").asBoolean(true))
                    .autoPublishCommands(inputParams.path("autoPublishCommands").asBoolean(true))
                    .autoPublishPages(inputParams.path("autoPublishPages").asBoolean(true))
                    .build();

            // Step 3: Execute import (25% - 95%)
            callback.report(25, "Importing resources...");

            ImportExecuteResult result = importService.execute(preview.getImportId(), importRequest);

            if (Thread.currentThread().isInterrupted()) {
                return AsyncTaskResult.fail("Import was cancelled");
            }

            // Step 4: Build result (95% - 100%)
            callback.report(95, "Finalizing...");

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("pluginId", result.getPluginId());
            resultNode.put("pluginPid", result.getPluginPid());
            resultNode.put("namespace", result.getNamespace());
            resultNode.put("version", result.getVersion());
            resultNode.put("success", result.isSuccess());
            resultNode.put("importId", result.getImportId());

            if (result.getResourceCounts() != null) {
                ObjectNode counts = objectMapper.createObjectNode();
                result.getResourceCounts().forEach((type, actionMap) -> {
                    ObjectNode typeNode = objectMapper.createObjectNode();
                    actionMap.forEach(typeNode::put);
                    counts.set(type, typeNode);
                });
                resultNode.set("resourceCounts", counts);
            }

            if (result.isSuccess()) {
                callback.report(99, "Plugin imported successfully: " + result.getPluginId());
                return AsyncTaskResult.ok(resultNode);
            } else {
                resultNode.put("errorMessage", result.getErrorMessage());
                return AsyncTaskResult.fail("Import failed: " + result.getErrorMessage());
            }

        } catch (Exception e) {
            log.error("Async plugin import failed: directory={}", directoryPath, e);
            return AsyncTaskResult.fail("Plugin import failed: " + e.getMessage());
        }
    }
}
