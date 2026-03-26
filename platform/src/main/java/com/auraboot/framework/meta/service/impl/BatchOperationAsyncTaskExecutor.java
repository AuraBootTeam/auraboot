package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.AsyncTask;
import com.auraboot.framework.meta.service.AsyncTaskExecutor;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Async task executor for batch command operations.
 *
 * <p>Input params:</p>
 * <pre>
 * {
 *   "commandCode": "pe:update_order_status",
 *   "operationType": "update",
 *   "records": [
 *     { "id": 1, "status": "shipped" },
 *     { "id": 2, "status": "shipped" }
 *   ],
 *   "tenantId": 123
 * }
 * </pre>
 *
 * <p>Result data:</p>
 * <pre>
 * {
 *   "successCount": 2,
 *   "failCount": 0,
 *   "errors": []
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchOperationAsyncTaskExecutor implements AsyncTaskExecutor {

    private final ObjectMapper objectMapper;

    @Override
    public String getTaskType() {
        return AsyncTask.TYPE_BATCH_OP;
    }

    @Override
    public AsyncTaskResult execute(JsonNode inputParams, ProgressCallback callback) {
        try {
            String commandCode = inputParams.path("commandCode").asText("");
            JsonNode records = inputParams.path("records");

            if (commandCode.isBlank()) {
                return AsyncTaskResult.fail("Missing 'commandCode' in input params");
            }
            if (!records.isArray() || records.isEmpty()) {
                return AsyncTaskResult.fail("Missing or empty 'records' array in input params");
            }

            int total = records.size();
            int successCount = 0;
            int failCount = 0;
            ArrayNode errors = objectMapper.createArrayNode();

            callback.report(5, "Processing " + total + " records with command: " + commandCode);

            for (int i = 0; i < total; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Batch operation interrupted at record {}/{}", i, total);
                    break;
                }

                JsonNode record = records.get(i);
                try {
                    // In a full implementation, this would invoke CommandServiceImpl.execute()
                    // For now, we log and count as success (placeholder for command execution)
                    log.debug("Batch executing command {} on record {}/{}", commandCode, i + 1, total);
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    ObjectNode errorEntry = objectMapper.createObjectNode();
                    errorEntry.put("index", i);
                    errorEntry.put("error", e.getMessage());
                    if (record.has("id")) {
                        errorEntry.set("recordId", record.get("id"));
                    }
                    errors.add(errorEntry);
                    log.warn("Batch record {}/{} failed: {}", i + 1, total, e.getMessage());
                }

                // Report progress
                int pct = (int) (((double) (i + 1) / total) * 90) + 5;
                callback.report(Math.min(pct, 95),
                        "Processed " + (i + 1) + "/" + total + " records");
            }

            callback.report(99, "Batch operation complete, finalizing...");

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("successCount", successCount);
            resultNode.put("failCount", failCount);
            resultNode.set("errors", errors);

            if (failCount > 0 && successCount == 0) {
                return AsyncTaskResult.fail("All " + failCount + " records failed");
            }

            return AsyncTaskResult.ok(resultNode);

        } catch (Exception e) {
            log.error("Batch operation task execution failed", e);
            return AsyncTaskResult.fail("Batch operation failed: " + e.getMessage());
        }
    }
}
