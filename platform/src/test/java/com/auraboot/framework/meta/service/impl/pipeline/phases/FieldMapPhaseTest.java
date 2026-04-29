package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.CommandCascadeDeleteExecutor;
import com.auraboot.framework.meta.service.impl.CommandFieldMapExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FieldMapPhaseTest {

    @Mock
    private CommandFieldMapExecutor fieldMapExecutor;

    @Mock
    private CommandCascadeDeleteExecutor cascadeDeleteExecutor;

    @Mock
    private RecordSnapshotReader snapshotReader;

    @Mock
    private ExtensionRegistry extensionRegistry;

    @Mock
    private CommandHandlerExtension pluginHandler;

    @Test
    void executeSkipsImplicitStateTransitionWhenPluginHandlerDisablesDslPersistence() {
        FieldMapPhase phase = new FieldMapPhase(
                fieldMapExecutor, cascadeDeleteExecutor, snapshotReader, extensionRegistry);

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("state_transition");
        request.setTargetRecordId("approval-1");

        CommandDefinition command = new CommandDefinition();
        command.setCode("acp:approve_request");
        command.setModelCode("agent_approval");

        Map<String, Object> execConfig = new HashMap<>(Map.of(
                "type", "state_transition",
                "stateField", "approval_status",
                "toState", "approved"
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

        when(extensionRegistry.getCommandHandler("acp:approve_request")).thenReturn(Optional.of(pluginHandler));
        when(pluginHandler.requiresDslPersistence("acp:approve_request", execConfig, request)).thenReturn(false);

        phase.execute(ctx);

        verify(extensionRegistry).getCommandHandler("acp:approve_request");
        verify(pluginHandler).requiresDslPersistence("acp:approve_request", execConfig, request);
        verify(fieldMapExecutor, never()).executeImplicitFieldMapPhase(
                same(execConfig), same(ctx.getPayload()), eq(1L), same(request), same(command));
        assertThat(ctx.isHasPluginHandler()).isTrue();
        assertThat(ctx.isPluginRequiresDslPersistence()).isFalse();
        assertThat(ctx.getFieldMapResults()).isEmpty();
    }
}
