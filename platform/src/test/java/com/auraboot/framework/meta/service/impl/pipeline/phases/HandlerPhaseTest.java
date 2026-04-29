package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandlerPhaseTest {

    private static final String BUSINESS_COMMAND_CODE = "pr:submit_purchase_order";
    private static final String PLUGIN_HANDLER_CODE = "pr:start_approval_flow";

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private ExtensionRegistry extensionRegistry;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private DynamicDataService dynamicDataService;

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private RecordSnapshotReader snapshotReader;

    @InjectMocks
    private HandlerPhase phase;

    @Test
    void execute_usesConfiguredPluginHandlerAndPassesHandlerParamsAsSettings() throws Exception {
        RecordingPluginHandler handler = new RecordingPluginHandler(PLUGIN_HANDLER_CODE);
        when(extensionRegistry.getCommandHandler(PLUGIN_HANDLER_CODE)).thenReturn(Optional.of(handler));
        when(metaModelService.getModelDefinition("pr_purchase_order")).thenReturn(Optional.empty());

        CommandPipelineContext ctx = buildContext(BUSINESS_COMMAND_CODE, "pr_purchase_order", Map.of(
                "type", "state_transition",
                "handler", PLUGIN_HANDLER_CODE,
                "handlerParams", Map.of(
                        "processKey", "po_approval",
                        "amountField", "pr_po_total_amount",
                        "statusField", "pr_po_status"
                )
        ));

        phase.execute(ctx);

        verify(extensionRegistry).getCommandHandler(PLUGIN_HANDLER_CODE);
        verify(extensionRegistry, never()).getCommandHandler(BUSINESS_COMMAND_CODE);
        assertThat(handler.capturedContext.get()).isNotNull();
        assertThat(handler.capturedContext.get().commandType()).isEqualTo(PLUGIN_HANDLER_CODE);
        assertThat(handler.capturedContext.get().namespace()).isEqualTo("pr");
        assertThat(handler.capturedContext.get().recordId()).isEqualTo("po-1");
        assertThat(handler.capturedContext.get().settings())
                .containsEntry("processKey", "po_approval")
                .containsEntry("amountField", "pr_po_total_amount")
                .containsEntry("statusField", "pr_po_status")
                .containsEntry("__commandCode", BUSINESS_COMMAND_CODE)
                .containsEntry("__handlerCode", PLUGIN_HANDLER_CODE)
                .containsKey(CommandHandlerExtension.DATA_ACCESSOR_KEY);
        assertThat(ctx.getHandlerResults()).containsEntry("observedProcessKey", "po_approval");
    }

    @Test
    void execute_fallsBackToCommandCodeWhenNoConfiguredPluginHandler() throws Exception {
        RecordingPluginHandler handler = new RecordingPluginHandler(BUSINESS_COMMAND_CODE);
        when(extensionRegistry.getCommandHandler(BUSINESS_COMMAND_CODE)).thenReturn(Optional.of(handler));

        CommandPipelineContext ctx = buildContext(BUSINESS_COMMAND_CODE, null, Map.of("type", "custom"));

        phase.execute(ctx);

        verify(extensionRegistry).getCommandHandler(BUSINESS_COMMAND_CODE);
        assertThat(handler.capturedContext.get()).isNotNull();
        assertThat(handler.capturedContext.get().commandType()).isEqualTo(BUSINESS_COMMAND_CODE);
        assertThat(handler.capturedContext.get().settings())
                .containsEntry("__commandCode", BUSINESS_COMMAND_CODE)
                .containsEntry("__handlerCode", BUSINESS_COMMAND_CODE);
    }

    private CommandPipelineContext buildContext(String commandCode, String modelCode, Map<String, Object> execConfig) {
        CommandDefinition command = new CommandDefinition();
        command.setCode(commandCode);
        command.setModelCode(modelCode);

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(Collections.emptyMap());
        request.setDryRun(false);
        request.setTargetRecordId("po-1");

        return CommandPipelineContext.builder()
                .commandCode(command.getCode())
                .request(request)
                .tenantId(1L)
                .userId(2L)
                .startTime(System.currentTimeMillis())
                .command(command)
                .payload(new HashMap<>())
                .execConfig(new HashMap<>(execConfig))
                .rulesByType(new HashMap<>())
                .fieldMapResults(new HashMap<>())
                .handlerResults(new HashMap<>())
                .build();
    }

    private static class RecordingPluginHandler implements CommandHandlerExtension {

        private final String commandType;
        private final AtomicReference<CommandContext> capturedContext = new AtomicReference<>();

        private RecordingPluginHandler(String commandType) {
            this.commandType = commandType;
        }

        @Override
        public String getCommandType() {
            return commandType;
        }

        @Override
        public Object execute(CommandContext context) {
            capturedContext.set(context);
            Map<String, Object> result = new HashMap<>();
            if (context.settings().containsKey("processKey")) {
                result.put("observedProcessKey", context.settings().get("processKey"));
            }
            result.put("observedCommandType", context.commandType());
            return result;
        }
    }
}
