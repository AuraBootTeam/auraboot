package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.meta.service.AsyncTaskExecutor.ProgressCallback;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PluginImportAsyncTaskExecutor — covers param validation,
 * preview-failure short-circuit, and happy-path success result building.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginImportAsyncTaskExecutor Unit Tests")
class PluginImportAsyncTaskExecutorTest {

    @Mock private PluginImportService importService;
    @Mock private ProgressCallback callback;

    // Use a real ObjectMapper for ObjectNode building (cheap and deterministic).
    private final ObjectMapper realMapper = new ObjectMapper();

    private PluginImportAsyncTaskExecutor executor;

    @BeforeEach
    void setup() {
        executor = new PluginImportAsyncTaskExecutor(importService, realMapper);
    }

    private ObjectNode params(Map<String, Object> overrides) {
        ObjectNode node = realMapper.createObjectNode();
        node.put("directoryPath", "/tmp/some-plugin");
        node.put("tenantId", 100L);
        node.put("userId", 1L);
        node.put("username", "tester");
        overrides.forEach((k, v) -> node.putPOJO(k, v));
        return node;
    }

    @Test
    @DisplayName("getTaskType returns the constant")
    void taskTypeIsConstant() {
        assertThat(executor.getTaskType()).isEqualTo(PluginImportAsyncTaskExecutor.TASK_TYPE);
    }

    @Test
    @DisplayName("execute fails when directoryPath missing")
    void shouldFailWhenDirectoryPathMissing() {
        ObjectNode node = realMapper.createObjectNode();
        node.put("tenantId", 100L);

        AsyncTaskResult result = executor.execute(node, callback);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("directoryPath");
        verify(importService, never()).parseDirectory(anyString());
    }

    @Test
    @DisplayName("execute fails when tenantId missing")
    void shouldFailWhenTenantIdMissing() {
        ObjectNode node = realMapper.createObjectNode();
        node.put("directoryPath", "/x");

        AsyncTaskResult result = executor.execute(node, callback);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("tenantId");
    }

    @Test
    @DisplayName("execute returns failure when preview is invalid")
    void shouldFailOnInvalidPreview() {
        ImportPreviewResult preview = ImportPreviewResult.builder()
                .valid(false)
                .errors(List.of("missing manifest", "bad version"))
                .build();
        when(importService.parseDirectory(anyString())).thenReturn(preview);

        AsyncTaskResult result = executor.execute(params(Map.of()), callback);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Invalid plugin").contains("missing manifest");
        verify(importService, never()).execute(anyString(), any(ImportRequest.class));
    }

    @Test
    @DisplayName("execute returns success result with serialized resourceCounts")
    void shouldReturnSuccessAndSerializeCounts() {
        ImportPreviewResult preview = ImportPreviewResult.builder()
                .valid(true)
                .importId("IMP-1")
                .pluginId("p")
                .version("1.0.0")
                .build();
        ImportExecuteResult execResult = ImportExecuteResult.builder()
                .success(true)
                .importId("IMP-1")
                .pluginPid("PLG-1")
                .pluginId("p")
                .namespace("ns")
                .version("1.0.0")
                .resourceCounts(Map.of(
                        "model", Map.of("created", 2, "updated", 1)))
                .build();

        when(importService.parseDirectory(anyString())).thenReturn(preview);
        when(importService.execute(anyString(), any(ImportRequest.class))).thenReturn(execResult);

        AsyncTaskResult result = executor.execute(params(Map.of("conflictStrategy", "OVERWRITE")), callback);

        assertThat(result.isSuccess()).isTrue();
        JsonNode data = result.getData();
        assertThat(data.get("pluginId").asText()).isEqualTo("p");
        assertThat(data.get("success").asBoolean()).isTrue();
        assertThat(data.get("resourceCounts").get("model").get("created").asInt()).isEqualTo(2);
        verify(callback, atLeastOnce()).report(anyInt(), anyString());
    }

    @Test
    @DisplayName("execute returns failure when import service reports failure")
    void shouldReturnFailureWhenImportFails() {
        ImportPreviewResult preview = ImportPreviewResult.builder()
                .valid(true).importId("IMP-1").pluginId("p").version("1.0.0").build();
        ImportExecuteResult execResult = ImportExecuteResult.builder()
                .success(false)
                .importId("IMP-1")
                .pluginId("p")
                .errorMessage("conflict")
                .build();

        when(importService.parseDirectory(anyString())).thenReturn(preview);
        when(importService.execute(anyString(), any(ImportRequest.class))).thenReturn(execResult);

        AsyncTaskResult result = executor.execute(params(Map.of("conflictStrategy", "OVERWRITE")), callback);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Import failed").contains("conflict");
    }

    @Test
    @DisplayName("execute uses requested conflictStrategy from input params")
    void shouldUseConflictStrategyFromParams() {
        ImportPreviewResult preview = ImportPreviewResult.builder()
                .valid(true).importId("IMP-1").pluginId("p").version("1.0.0").build();
        ImportExecuteResult exec = ImportExecuteResult.builder()
                .success(true).importId("IMP-1").pluginId("p").pluginPid("PLG").version("1.0.0").build();
        when(importService.parseDirectory(anyString())).thenReturn(preview);
        when(importService.execute(anyString(), any(ImportRequest.class))).thenReturn(exec);

        executor.execute(params(Map.of("conflictStrategy", "SKIP")), callback);

        ArgumentCaptor<ImportRequest> captor = ArgumentCaptor.forClass(ImportRequest.class);
        verify(importService).execute(anyString(), captor.capture());
        assertThat(captor.getValue().getConflictStrategy())
                .isEqualTo(ImportRequest.ConflictStrategy.SKIP);
    }

    @Test
    @DisplayName("execute returns failure when underlying service throws")
    void shouldReturnFailureWhenServiceThrows() {
        when(importService.parseDirectory(anyString()))
                .thenThrow(new RuntimeException("boom"));

        AsyncTaskResult result = executor.execute(params(Map.of()), callback);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Plugin import failed").contains("boom");
    }
}
