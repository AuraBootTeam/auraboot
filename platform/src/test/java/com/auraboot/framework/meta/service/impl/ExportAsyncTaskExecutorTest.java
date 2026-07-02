package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Regression tests for {@link ExportAsyncTaskExecutor} format routing.
 *
 * <p>Guards the case-mismatch fix (AGENTS §9): the switch value is {@code format.toUpperCase()},
 * so the case labels must be uppercase. Previously they were lowercase ({@code "csv"}/{@code "json"}),
 * making the {@code json} arm unreachable — a JSON export silently produced a CSV file.
 */
@ExtendWith(MockitoExtension.class)
class ExportAsyncTaskExecutorTest {

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExportAsyncTaskExecutor newExecutor() {
        return new ExportAsyncTaskExecutor(dynamicDataMapper, objectMapper);
    }

    private ObjectNode params(String format) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("modelCode", "tnt");
        node.put("sql", "SELECT id FROM ab_tenant");
        node.put("format", format);
        node.put("tenantId", 1);
        return node;
    }

    private void stubOneRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("name", "alpha");
        when(dynamicDataMapper.countByQuery(anyString(), any())).thenReturn(1L);
        when(dynamicDataMapper.selectByQuery(anyString(), any())).thenReturn(List.of(row));
    }

    @Test
    @DisplayName("format=json produces a JSON file (regression: previously fell through to CSV)")
    void jsonFormatProducesJsonFile() throws Exception {
        stubOneRow();
        AsyncTaskResult result = newExecutor().execute(params("json"), (pct, msg) -> { });

        assertThat(result.isSuccess()).isTrue();
        String fileUrl = result.getData().get("fileUrl").asText();
        assertThat(fileUrl).endsWith(".json");
        JsonNode written = objectMapper.readTree(Files.readString(Path.of(fileUrl)));
        assertThat(written.isArray()).isTrue();
        assertThat(written.get(0).get("name").asText()).isEqualTo("alpha");
    }

    @Test
    @DisplayName("format=csv produces a CSV file")
    void csvFormatProducesCsvFile() throws Exception {
        stubOneRow();
        AsyncTaskResult result = newExecutor().execute(params("csv"), (pct, msg) -> { });

        assertThat(result.isSuccess()).isTrue();
        String fileUrl = result.getData().get("fileUrl").asText();
        assertThat(fileUrl).endsWith(".csv");
        assertThat(Files.readString(Path.of(fileUrl))).startsWith("id,name");
    }

    @Test
    @DisplayName("uppercase JSON is matched case-insensitively (switch value is toUpperCase)")
    void uppercaseJsonAlsoProducesJson() throws Exception {
        stubOneRow();
        AsyncTaskResult result = newExecutor().execute(params("JSON"), (pct, msg) -> { });
        assertThat(result.getData().get("fileUrl").asText()).endsWith(".json");
    }
}
