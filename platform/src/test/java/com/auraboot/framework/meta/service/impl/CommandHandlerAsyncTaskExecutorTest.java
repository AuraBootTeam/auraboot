package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.AsyncTaskExecutor.ProgressCallback;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandHandlerAsyncTaskExecutorTest {

    private ExtensionRegistry extensionRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CommandHandlerAsyncTaskExecutor executor;
    private final ProgressCallback noop = (pct, msg) -> {};

    @BeforeEach
    void setUp() {
        extensionRegistry = mock(ExtensionRegistry.class);
        DynamicDataService dynamicDataService = mock(DynamicDataService.class);
        executor = new CommandHandlerAsyncTaskExecutor(extensionRegistry, objectMapper, dynamicDataService);
    }

    private ObjectNode params(String handlerCode) {
        ObjectNode in = objectMapper.createObjectNode();
        if (handlerCode != null) in.put("handlerCode", handlerCode);
        in.put("commandCode", "bom:import_material_library");
        in.put("tenantId", 123L);
        in.put("userId", 45L);
        in.put("modelCode", "bom_material_master");
        ObjectNode payload = in.putObject("payload");
        payload.put("source_file_id", "01KFILE");
        return in;
    }

    @Test
    void taskTypeIsCommandHandler() {
        assertThat(executor.getTaskType()).isEqualTo("command-handler");
    }

    @Test
    void runsResolvedHandlerAndReturnsResultData() {
        CommandHandlerExtension handler = mock(CommandHandlerExtension.class);
        try {
            when(handler.execute(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Map.of("success", true, "importedRows", 35924));
        } catch (Exception ignored) {
            // mock stub, never thrown here
        }
        when(extensionRegistry.getCommandHandler(eq("bom:import_material_library")))
                .thenReturn(Optional.of(handler));

        AsyncTaskResult result = executor.execute(params("bom:import_material_library"), noop);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().get("importedRows").asInt()).isEqualTo(35924);
        assertThat(result.getData().get("success").asBoolean()).isTrue();
    }

    @Test
    void failsWhenHandlerNotRegistered() {
        when(extensionRegistry.getCommandHandler(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        AsyncTaskResult result = executor.execute(params("bom:missing"), noop);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("No plugin command handler");
    }

    @Test
    void failsWhenHandlerThrows() {
        CommandHandlerExtension handler = mock(CommandHandlerExtension.class);
        try {
            when(handler.execute(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new IllegalStateException("source_file_id is required"));
        } catch (Exception ignored) {
            // mock stub
        }
        when(extensionRegistry.getCommandHandler(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(handler));

        AsyncTaskResult result = executor.execute(params("bom:import_material_library"), noop);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("source_file_id is required");
    }

    @Test
    void failsOnMissingHandlerCode() {
        AsyncTaskResult result = executor.execute(params(null), noop);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("handlerCode");
    }
}
