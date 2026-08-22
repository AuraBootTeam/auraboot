package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.AsyncTaskExecutor.ProgressCallback;
import com.auraboot.framework.meta.service.AsyncTaskResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.RecordShareAccessor;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.net.ConnectException;

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

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    private ObjectNode params(String handlerCode) {
        ObjectNode in = objectMapper.createObjectNode();
        if (handlerCode != null) in.put("handlerCode", handlerCode);
        in.put("commandCode", "bom:import_material_library");
        in.put("tenantId", 123L);
        in.put("userId", 45L);
        in.put("currentUserPid", "user-pid-45");
        in.put("modelCode", "bom_material_master");
        in.put("clientRequestId", "client-request-async-1");
        in.put("commandExpectedVersion", 7L);
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
    void injectsAuthenticatedUserAsServerOwnedPluginSetting() throws Exception {
        AtomicReference<CommandHandlerExtension.CommandContext> captured = new AtomicReference<>();
        RecordShareAccessor recordShareAccessor = mock(RecordShareAccessor.class);
        ReflectionTestUtils.setField(executor, "recordShareAccessor", recordShareAccessor);
        CommandHandlerExtension handler = mock(CommandHandlerExtension.class);
        when(handler.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return Map.of("success", true);
        });
        when(extensionRegistry.getCommandHandler(eq("bom:import_material_library")))
                .thenReturn(Optional.of(handler));

        AsyncTaskResult result = executor.execute(params("bom:import_material_library"), noop);

        assertThat(result.isSuccess()).isTrue();
        assertThat(captured.get().settings()).containsEntry("__currentUser", "45");
        assertThat(captured.get().currentUserPid()).isEqualTo("user-pid-45");
        assertThat(captured.get().recordShareAccessor()).isSameAs(recordShareAccessor);
        assertThat(captured.get().clientRequestId()).isEqualTo("client-request-async-1");
        assertThat(captured.get().expectedVersion()).isEqualTo(7L);
    }

    @Test
    void restoresExactCommandWriterIdentityOnlyFromPersistedPermitPlan() throws Exception {
        AtomicReference<String> commandSeen = new AtomicReference<>();
        CommandHandlerExtension handler = mock(CommandHandlerExtension.class);
        when(handler.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            commandSeen.set(MetaContext.getAuthorizedCommandCode());
            return Map.of("success", true);
        });
        when(extensionRegistry.getCommandHandler(eq("bom:import_material_library")))
                .thenReturn(Optional.of(handler));
        ObjectNode input = params("bom:import_material_library");
        input.put("commandPermitScope", "ALL");

        AsyncTaskResult result = executor.execute(input, noop);

        assertThat(result.isSuccess()).isTrue();
        assertThat(commandSeen).hasValue("bom:import_material_library");
        assertThat(MetaContext.getAuthorizedCommandCode()).isNull();
    }

    @Test
    void commandCodeWithoutPersistedPermitPlanDoesNotBecomeWriterAuthority() throws Exception {
        AtomicReference<String> commandSeen = new AtomicReference<>("not-run");
        CommandHandlerExtension handler = mock(CommandHandlerExtension.class);
        when(handler.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            commandSeen.set(MetaContext.getAuthorizedCommandCode());
            return Map.of("success", true);
        });
        when(extensionRegistry.getCommandHandler(eq("bom:import_material_library")))
                .thenReturn(Optional.of(handler));

        AsyncTaskResult result = executor.execute(params("bom:import_material_library"), noop);

        assertThat(result.isSuccess()).isTrue();
        assertThat(commandSeen.get()).isNull();
    }

    @Test
    void persistedPermitPlanWithoutCommandCodeDoesNotTrustHandlerIdentity() throws Exception {
        AtomicReference<String> commandSeen = new AtomicReference<>("not-run");
        CommandHandlerExtension handler = mock(CommandHandlerExtension.class);
        when(handler.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            commandSeen.set(MetaContext.getAuthorizedCommandCode());
            return Map.of("success", true);
        });
        when(extensionRegistry.getCommandHandler(eq("bom:import_material_library")))
                .thenReturn(Optional.of(handler));
        ObjectNode input = params("bom:import_material_library");
        input.remove("commandCode");
        input.put("commandPermitScope", "ALL");

        AsyncTaskResult result = executor.execute(input, noop);

        assertThat(result.isSuccess()).isTrue();
        assertThat(commandSeen.get()).isNull();
    }

    @Test
    void failsWhenHandlerNotRegistered() {
        when(extensionRegistry.getCommandHandler(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        AsyncTaskResult result = executor.execute(params("bom:missing"), noop);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("No plugin command handler");
        assertThat(result.isRetryable()).isFalse();
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
        assertThat(result.isRetryable()).isFalse();
        assertThat(DynamicDataQueryScope.isActive()).isFalse();
    }

    @Test
    void marksTypedTransientInfrastructureFailureRetryable() throws Exception {
        CommandHandlerExtension handler = mock(CommandHandlerExtension.class);
        when(handler.execute(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ConnectException("provider temporarily unreachable"));
        when(extensionRegistry.getCommandHandler(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(handler));

        AsyncTaskResult result = executor.execute(params("bom:import_material_library"), noop);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsProgressReporterThatForwardsToTaskCallback() throws Exception {
        AtomicReference<BiConsumer<Integer, String>> captured = new AtomicReference<>();
        CommandHandlerExtension handler = mock(CommandHandlerExtension.class);
        when(handler.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            CommandHandlerExtension.CommandContext ctx = inv.getArgument(0);
            Object reporter = ctx.settings().get("__progressReporter");
            captured.set((BiConsumer<Integer, String>) reporter);
            return Map.of("success", true);
        });
        when(extensionRegistry.getCommandHandler(eq("bom:import_material_library")))
                .thenReturn(Optional.of(handler));

        AtomicReference<Integer> reportedPct = new AtomicReference<>();
        AtomicReference<String> reportedMsg = new AtomicReference<>();
        ProgressCallback recording = (pct, msg) -> {
            reportedPct.set(pct);
            reportedMsg.set(msg);
        };

        AsyncTaskResult result = executor.execute(params("bom:import_material_library"), recording);

        assertThat(result.isSuccess()).isTrue();
        assertThat(captured.get())
                .as("plugin settings must carry a __progressReporter BiConsumer")
                .isInstanceOf(BiConsumer.class);

        // Invoking the injected reporter forwards to the task ProgressCallback.
        captured.get().accept(42, "halfway");
        assertThat(reportedPct.get()).isEqualTo(42);
        assertThat(reportedMsg.get()).isEqualTo("halfway");
    }

    @Test
    void runsHandlerInsideDynamicDataQueryScopeAndClosesIt() throws Exception {
        AtomicReference<Boolean> scopeActiveDuringExecute = new AtomicReference<>(false);
        CommandHandlerExtension handler = mock(CommandHandlerExtension.class);
        when(handler.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            scopeActiveDuringExecute.set(DynamicDataQueryScope.isActive());
            return Map.of("success", true);
        });
        when(extensionRegistry.getCommandHandler(eq("bom:import_material_library")))
                .thenReturn(Optional.of(handler));

        AsyncTaskResult result = executor.execute(params("bom:import_material_library"), noop);

        assertThat(result.isSuccess()).isTrue();
        assertThat(scopeActiveDuringExecute.get()).isTrue();
        assertThat(DynamicDataQueryScope.isActive()).isFalse();
    }

    @Test
    void failsOnMissingHandlerCode() {
        AsyncTaskResult result = executor.execute(params(null), noop);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("handlerCode");
        assertThat(result.isRetryable()).isFalse();
    }
}
