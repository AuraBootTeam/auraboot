package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.phases.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeferredCommandPipelineTest {
    private final LoadPhase load = mock(LoadPhase.class);
    private final IdempotencyPhase cache = mock(IdempotencyPhase.class);
    private final CommandTargetVersionLockPhase lock = mock(CommandTargetVersionLockPhase.class);
    private final PermitPlanAssemblyPhase assembly = mock(PermitPlanAssemblyPhase.class);
    private final CommandPhase gate = mock(CommandPhase.class);
    private final CommandPhase writer = mock(CommandPhase.class);
    private final Map<String,Object> config = new HashMap<>();
    private CommandPipelineContext context;
    private CommandPipeline pipeline;

    @BeforeEach void setup() {
        config.put("type","custom"); config.put("handler","bom:process_conversion_task");
        config.put("handlerParams",Map.of("async",false));
        var command = new CommandDefinition(); command.setCode("bom:process_conversion_task");
        command.setModelCode("bom_conversion_task_pcba");
        var request = new CommandExecuteRequest(); request.setTargetRecordId("task-1"); request.setPayload(Map.of());
        context = CommandPipelineContext.builder().commandCode(command.getCode()).request(request).tenantId(1L).userId(42L).build();
        doAnswer(call -> { context.setCommand(command); context.setExecConfig(config); context.setTargetRecordReadable(true); return null; }).when(load).execute(context);
        doAnswer(call -> {
            context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("bom.convert.execute"));
            context.setPermitPlan(CommandPermitPlan.fromPhaseDecisions(List.of(CommandPermitPlan.PhaseDecision.permit("authorization")),
                    "task-1",CommandPermitPlan.ScopeGrade.TARGET,11L)); return null;
        }).when(assembly).execute(context);
        pipeline = new CommandPipeline(List.of(load),List.of(gate,cache,lock,assembly,writer));
    }

    private void authorize() { pipeline.authorizeDeferredHandler(context,"bom:process_conversion_task","bom_conversion_task_pcba"); }

    @Test void reusesGatesButNeverClaimsCachesLocksOrRunsMutationPhases() {
        authorize();
        var ordered = inOrder(load,gate,assembly);
        ordered.verify(load).execute(context); ordered.verify(gate).execute(context); ordered.verify(assembly).execute(context);
        verify(cache,never()).execute(any()); verify(lock,never()).execute(any()); verify(writer,never()).execute(any());
    }

    @Test void changedDefinitionCannotRedirectThePersistedInvocation() {
        config.put("handler","another:handler");
        assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class);
        verify(gate,never()).execute(any()); verify(assembly,never()).execute(any());
    }

    @Test void changedModelOrOperationIsNotTheSameInvocation() {
        assertThatThrownBy(() -> pipeline.authorizeDeferredHandler(context,"bom:process_conversion_task","foreign_model"))
                .isInstanceOf(AccessDeniedException.class);
        config.put("type","update");
        assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class);
        verify(gate,never()).execute(any());
    }

    @Test void businessResourceLoansCannotBecomeImplicitQueueAuthority() {
        config.put("handlerParams",Map.of("targetRecordAuthorization","business"));
        assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class);
        verify(gate,never()).execute(any());
    }

    @Test void refusalOrAbstentionCannotReachExecution() {
        doThrow(new AccessDeniedException("current refusal")).when(gate).execute(context);
        assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class).hasMessage("current refusal");
        verify(assembly,never()).execute(any());
        reset(gate);
        doAnswer(call -> { context.setPermitPlan(null); return null; }).when(assembly).execute(context);
        assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class);
    }

    @Test void staleExplicitVersionIsRefusedWithoutLockingOrWriting() {
        context.getRequest().setExpectedVersion(7); context.setTargetRecordVersion(11L);
        assertThatThrownBy(this::authorize).isInstanceOf(com.auraboot.framework.exception.CasVersionConflictException.class);
        verify(lock,never()).execute(any()); verify(writer,never()).execute(any());
    }

    @Test void missingAuthorizationBoundaryTerminatorsFailClosed() {
        var broken = new CommandPipeline(List.of(load),List.of(gate,assembly,writer));
        assertThatThrownBy(() -> broken.authorizeDeferredHandler(context,"bom:process_conversion_task","bom_conversion_task_pcba"))
                .isInstanceOf(AccessDeniedException.class);
        verify(writer,never()).execute(any());
    }
}
