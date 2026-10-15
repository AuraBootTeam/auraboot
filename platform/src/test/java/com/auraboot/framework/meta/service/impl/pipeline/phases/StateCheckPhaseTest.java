package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.CommandStateCheckExecutor;
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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class StateCheckPhaseTest {

    @Mock
    private CommandStateCheckExecutor stateCheckExecutor;

    @Mock
    private ExtensionRegistry extensionRegistry;

    @Mock
    private RecordSnapshotReader snapshotReader;

    @Mock
    private CommandHandlerExtension pluginHandler;

    @Test
    void capturesOriginalSnapshotBeforeStateCheckMutatesTheRecord() {
        CommandPipelineContext ctx = transitionContext();
        Map<String, Object> original = Map.of("order_status", "in_review");
        when(snapshotReader.readRecordSnapshot(99L, "orders", "owned-record")).thenReturn(original);
        when(stateCheckExecutor.executeStateCheckPhase(any(), any(), eq(99L), any(), any()))
                .thenAnswer(invocation -> {
                    assertThat(ctx.getBeforeSnapshot()).isSameAs(original);
                    return "done";
                });

        new StateCheckPhase(stateCheckExecutor, extensionRegistry, snapshotReader).execute(ctx);

        var order = inOrder(snapshotReader, stateCheckExecutor);
        order.verify(snapshotReader).readRecordSnapshot(99L, "orders", "owned-record");
        order.verify(stateCheckExecutor).executeStateCheckPhase(ctx.getCommand(), ctx.getPayload(),
                99L, ctx.getRequest(), ctx.getExecConfig());
        assertThat(ctx.getBeforeSnapshot()).containsEntry("order_status", "in_review");
        assertThat(ctx.getTargetState()).isEqualTo("done");
    }

    @Test
    void preservesAnAlreadyCapturedSnapshot() {
        CommandPipelineContext ctx = transitionContext();
        Map<String, Object> original = Map.of("order_status", "in_review");
        ctx.setBeforeSnapshot(original);
        new StateCheckPhase(stateCheckExecutor, extensionRegistry, snapshotReader).execute(ctx);
        verifyNoInteractions(snapshotReader);
        assertThat(ctx.getBeforeSnapshot()).isSameAs(original);
    }

    @Test
    void newRecordWithoutTargetDoesNotReadSnapshot() {
        CommandPipelineContext ctx = transitionContext();
        ctx.getRequest().setTargetRecordId(null);
        new StateCheckPhase(stateCheckExecutor, extensionRegistry, snapshotReader).execute(ctx);
        verifyNoInteractions(snapshotReader);
        assertThat(ctx.getBeforeSnapshot()).isNull();
    }

    private CommandPipelineContext transitionContext() {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId("owned-record");
        request.setOperationType("UPDATE");
        CommandDefinition command = new CommandDefinition();
        command.setCode("orders:complete");
        command.setModelCode("orders");
        return CommandPipelineContext.builder().request(request).command(command).tenantId(99L)
                .execConfig(Map.of("type", "state_transition", "stateField", "order_status", "toState", "done"))
                .build();
    }

    @Test
    void shouldSkipStateTransitionWhenPluginHandlerDisablesDslPersistence() {
        StateCheckPhase phase = new StateCheckPhase(stateCheckExecutor, extensionRegistry, snapshotReader);

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("update");
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

        assertThat(phase.shouldSkip(ctx)).isTrue();
        assertThat(ctx.isHasPluginHandler()).isTrue();
        assertThat(ctx.isPluginRequiresDslPersistence()).isFalse();
    }
}
