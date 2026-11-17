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
    @org.junit.jupiter.api.AfterEach void clearBusinessFixture() {
        com.auraboot.framework.application.tenant.MetaContext.clear();
        org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
    private CommandPipelineContext businessContext() {
        com.auraboot.framework.application.tenant.MetaContext.setContext(99L,7L,"actor","Actor");
        com.auraboot.framework.application.tenant.MetaContext.setMemberId(8L);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        var base=transitionContext();
        var ctx=CommandPipelineContext.builder().commandCode("orders:complete").command(base.getCommand()).request(base.getRequest())
                .tenantId(99L).userId(7L).payload(Map.of()).build();
        ctx.captureSubmittedInput(Map.of());
        ctx.setExecConfig(Map.of("type","state_transition","stateField","order_status","fromStates",java.util.List.of("in_review"),
                "toState","done","permissions",java.util.List.of("orders.manage"),"options",Map.of("targetVersionPolicy","current_state"),
                "handlerParams",Map.of("targetRecordAuthorization","business","targetRecordAction","update")));
        ctx.setAuthorizationVerdict(com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict.authorized("orders.manage"));
        ctx.setTargetRecordVersion(4L);
        ctx.setBusinessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization("orders:complete","orders","owned-record","update",99L,7L,8L));
        ctx.setBusinessGrantDecision(new com.auraboot.framework.permission.engine.model.BusinessGrantDecision(
                com.auraboot.framework.permission.engine.model.PermissionResult.allow(java.util.List.of()),99L,7L,8L,"orders","owned-record",
                java.util.List.of(new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Grant(1L,2L,"orders.manage",
                        new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Fields(false,java.util.Set.of(),java.util.Set.of())))));
        return ctx;
    }
    private void executeBusiness(CommandPipelineContext ctx) {
        com.auraboot.framework.application.tenant.MetaContext.runWithCommandPermitPlan("TARGET",4L,"orders","owned-record",() ->
            com.auraboot.framework.application.tenant.MetaContext.runWithAuthorizedCommandCode("orders:complete",() -> {
                new StateCheckPhase(stateCheckExecutor,extensionRegistry,snapshotReader).execute(ctx);return null;
            }));
    }
    private Map<String,Object> stored(String state,long tenant,long version) {
        return Map.of("pid","owned-record","tenant_id",tenant,"row_version",version,"order_status",state,"deleted_flag",false);
    }
    @Test void fixedBusinessStateIsValidatedWithoutTheLegacyEarlyMutationOrModelPermissions() {
        var ctx=businessContext();var row=stored("in_review",99L,4L);
        when(snapshotReader.readRecordSnapshot(99L,"orders","owned-record")).thenReturn(row);
        executeBusiness(ctx);
        assertThat(ctx.getBeforeSnapshot()).isEqualTo(row);assertThat(ctx.getTargetState()).isEqualTo("done");
        verifyNoInteractions(stateCheckExecutor);
    }
    @Test void wrongStateCannotBeWrittenOrDisclosedThroughTheBusinessTransition() {
        var ctx=businessContext();when(snapshotReader.readRecordSnapshot(99L,"orders","owned-record")).thenReturn(stored("private_state",99L,4L));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executeBusiness(ctx)).hasMessage("$i18n:command.transition.not_allowed");
        verifyNoInteractions(stateCheckExecutor);assertThat(ctx.getTargetState()).isNull();
    }
    @Test void missingForeignOrChangedSnapshotCannotFallThroughToAnImplicitTransition() {
        var ctx=businessContext();
        when(snapshotReader.readRecordSnapshot(99L,"orders","owned-record")).thenReturn(null,stored("in_review",100L,4L),stored("in_review",99L,5L));
        for(int i=0;i<3;i++)org.assertj.core.api.Assertions.assertThatThrownBy(() -> executeBusiness(ctx)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(stateCheckExecutor);
    }
    @Test void missingDecisionOrPermissionScopeCannotManufactureATransitionWitness() {
        var ctx=businessContext();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new StateCheckPhase(stateCheckExecutor,extensionRegistry,snapshotReader).execute(ctx))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        ctx.setBusinessGrantDecision(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executeBusiness(ctx)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(snapshotReader,stateCheckExecutor);
    }
}
