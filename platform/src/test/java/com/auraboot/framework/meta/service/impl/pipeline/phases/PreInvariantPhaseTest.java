package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.InvariantEngine;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.CommandSpelEvaluator;
import com.auraboot.framework.meta.service.impl.CommandStateCheckExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreInvariantPhaseTest {

    private static final String BUSINESS_COMMAND_CODE = "pr:submit_purchase_order";
    private static final String PLUGIN_HANDLER_CODE = "pr:start_approval_flow";

    @Mock
    private InvariantEngine invariantEngine;

    @Mock
    private CommandStateCheckExecutor stateCheckExecutor;

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private CommandSpelEvaluator spelEvaluator;

    @Mock
    private ExtensionRegistry extensionRegistry;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private CommandHandlerExtension pluginHandler;

    @InjectMocks
    private PreInvariantPhase phase;

    @Test
    void execute_resolvesPluginHandlerFlagsFromExecutionConfigHandler() {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId("po-1");

        CommandDefinition command = new CommandDefinition();
        command.setCode(BUSINESS_COMMAND_CODE);
        command.setModelCode("pr_purchase_order");

        Map<String, Object> execConfig = new HashMap<>(Map.of(
                "type", "state_transition",
                "handler", PLUGIN_HANDLER_CODE,
                "handlerParams", Map.of("processKey", "po_approval")
        ));

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode(command.getCode())
                .request(request)
                .tenantId(1L)
                .userId(2L)
                .startTime(System.currentTimeMillis())
                .command(command)
                .payload(new HashMap<>())
                .execConfig(execConfig)
                .build();

        when(stateCheckExecutor.getStateFieldForModel("pr_purchase_order")).thenReturn(null);
        when(invariantEngine.evaluatePreInvariants(
                eq(1L), eq(BUSINESS_COMMAND_CODE), eq("pr_purchase_order"),
                same(ctx.getPayload()), eq("po-1"), eq(null)))
                .thenReturn(List.of());
        when(metaModelService.getModelDefinition("pr_purchase_order")).thenReturn(Optional.empty());
        when(extensionRegistry.getCommandHandler(PLUGIN_HANDLER_CODE)).thenReturn(Optional.of(pluginHandler));
        when(pluginHandler.requiresDslPersistence(PLUGIN_HANDLER_CODE, execConfig, request)).thenReturn(true);

        phase.execute(ctx);

        verify(extensionRegistry).getCommandHandler(PLUGIN_HANDLER_CODE);
        verify(extensionRegistry, never()).getCommandHandler(BUSINESS_COMMAND_CODE);
        assertThat(ctx.isHasPluginHandler()).isTrue();
        assertThat(ctx.isPluginRequiresDslPersistence()).isTrue();
    }
}
