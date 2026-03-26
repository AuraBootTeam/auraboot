package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.AsyncTask;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.DynamicSqlProvider;
import com.auraboot.framework.meta.service.AsyncTaskExecutor;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Async task executor for data export operations.
 *
 * <p>Input params:</p>
 * <pre>
 * {
 *   "modelCode": "pe_sales_order",
 *   "sql": "SELECT * FROM mt_pe_sales_order",
 *   "format": "csv",       // CSV, JSON, or EXCEL (default)
 *   "tenantId": 123
 * }
 * </pre>
 *
 * <p>Result data:</p>
 * <pre>
 * {
 *   "fileUrl": "/tmp/export_xxx.csv",
 *   "recordCount": 1500
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportAsyncTaskExecutor implements AsyncTaskExecutor {

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getTaskType() {
        return AsyncTask.TYPE_EXPORT;
    }

    @Override
    public AsyncTaskResult execute(JsonNode inputParams, ProgressCallback callback) {
        try {
            String modelCode = inputParams.path("modelCode").asText("");
            String sql = inputParams.path("sql").asText("");
            String format = inputParams.path("format").asText("excel");
            long tenantId = inputParams.path("tenantId").asLong(0);

            if (sql.isBlank()) {
                return AsyncTaskResult.fail("Missing 'sql' in input params");
            }

            // Validate SQL safety: only SELECT allowed, no injection
            DynamicSqlProvider.validateExportSql(sql);

            callback.report(5, "Counting records...");

            Map<String, Object> params = new HashMap<>();
            params.put("tenantId", tenantId);

            // Count total records
            String countSql = "SELECT COUNT(*) FROM (" + sql + ") AS _cnt";
            Long total = dynamicDataMapper.countByQuery(countSql, params);

            callback.report(10, "Fetching " + total + " records...");

            // Execute query
            List<Map<String, Object>> data = dynamicDataMapper.selectByQuery(sql, params);

            callback.report(60, "Writing " + data.size() + " records to " + format + " file...");

            // Write to file
            String fileName = (modelCode.isBlank() ? "export" : modelCode) + "_" + System.currentTimeMillis();
            Path filePath;

            switch (format.toUpperCase()) {
                case "csv":
                    filePath = writeCsv(data, fileName);
                    break;
                case "json":
                    filePath = writeJson(data, fileName);
                    break;
                default:
                    // Default to CSV for simplicity (Excel requires POI dependency context)
                    filePath = writeCsv(data, fileName);
                    break;
            }

            callback.report(95, "Export complete, finalizing...");

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("fileUrl", filePath.toString());
            resultNode.put("recordCount", data.size());

            return AsyncTaskResult.ok(resultNode);

        } catch (Exception e) {
            log.error("Export task execution failed", e);
            return AsyncTaskResult.fail("Export failed: " + e.getMessage());
        }
    }

    private Path writeCsv(List<Map<String, Object>> data, String fileName) throws Exception {
        Path tempFile = Files.createTempFile(fileName, ".csv");
        if (data.isEmpty()) {
            return tempFile;
        }

        List<String> columns = data.get(0).keySet().stream().toList();

        try (BufferedWriter writer = Files.newBufferedWriter(tempFile)) {
            // Header
            writer.write(String.join(",", columns));
            writer.newLine();

            // Data
            for (Map<String, Object> row : data) {
                List<String> values = columns.stream()
                        .map(col -> {
                            Object val = row.get(col);
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

    private Path writeJson(List<Map<String, Object>> data, String fileName) throws Exception {
        Path tempFile = Files.createTempFile(fileName, ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), data);
        return tempFile;
    }
}
