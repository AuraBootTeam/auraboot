package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.CommandInvocationAuthorizer;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The real invocation phase must attest the exact target before assembly preserves its scope. */
class InvocationTargetPermitPlanTest {
    private final ExtensionRegistry registry = mock(ExtensionRegistry.class);
    private final CommandInvocationAuthorizationPhase invocation = new CommandInvocationAuthorizationPhase(registry);
    private final PermitPlanAssemblyPhase assembly = new PermitPlanAssemblyPhase();
    private final CommandHandlerExtension handler = mock(CommandHandlerExtension.class,
            withSettings().extraInterfaces(CommandInvocationAuthorizer.class));

    @BeforeEach void setup() {
        MetaContext.setContext(7L, 9L, "user-9", "target-plan-test");
        MetaContext.setMemberId(11L);
        when(registry.getCommandHandler("resource:hold")).thenReturn(Optional.of(handler));
        lenient().when(((CommandInvocationAuthorizer) handler).authorizeInvocation(any())).thenReturn(true);
        var engine = mock(DataPermissionEngine.class);
        lenient().when(engine.buildRowFilter(anyLong(), anyString(), anyLong())).thenReturn("created_by = actor");
        ReflectionTestUtils.setField(assembly, "dataPermissionEngine", engine);
    }
    @AfterEach void clear() { MetaContext.clear(); }

    @ParameterizedTest @ValueSource(strings = {"update", "delete"})
    void successfulInvocationAndTargetBoundaryPreserveOnlyTheExactTarget(String action) {
        var ctx = context(action);
        invocation.execute(ctx);
        assembly.execute(ctx);
        assertThat(ctx.getPermitPlan().scope()).isEqualTo(CommandPermitPlan.ScopeGrade.TARGET);
        assertThat(ctx.getPermitPlan().aggregateId()).isEqualTo("resource-1");
        assertThat(ctx.getPermitPlan().expectedVersion()).isEqualTo(7L);
    }

    @Test void declarationWithoutACompletedInvocationCannotGrantTargetScope() {
        var ctx = context("update");
        assembly.execute(ctx);
        assertThat(ctx.getPermitPlan().scope()).isEqualTo(CommandPermitPlan.ScopeGrade.SELF);
    }

    @Test void domainDenialCannotGrantTargetScope() {
        var ctx = context("update");
        when(((CommandInvocationAuthorizer) handler).authorizeInvocation(any())).thenReturn(false);
        assertThatThrownBy(() -> invocation.execute(ctx)).isInstanceOf(AccessDeniedException.class);
        assembly.execute(ctx);
        assertThat(ctx.getPermitPlan().scope()).isEqualTo(CommandPermitPlan.ScopeGrade.SELF);
    }

    @Test void successDoesNotBypassTargetReadOrFunctionAuthorization() {
        for (boolean readable : new boolean[] {false, true}) {
            var ctx = context("update");
            invocation.execute(ctx);
            if (!readable) ctx.setTargetRecordReadable(false);
            else ctx.setAuthorizationVerdict(null);
            assembly.execute(ctx);
            assertThat(ctx.getPermitPlan().scope()).isNotEqualTo(CommandPermitPlan.ScopeGrade.TARGET);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"target", "model", "action"})
    void anInvocationForAnotherTargetModelOrActionCannotGrantTargetScope(String changed) {
        var ctx = context("update");
        invocation.execute(ctx);
        if (changed.equals("target")) ctx.getRequest().setTargetRecordId("sibling-resource");
        if (changed.equals("model")) ctx.getCommand().setModelCode("another_model");
        if (changed.equals("action")) ctx.setExecConfig(config("delete"));
        assembly.execute(ctx);
        assertThat(ctx.getPermitPlan().scope()).isNotEqualTo(CommandPermitPlan.ScopeGrade.TARGET);
    }

    @Test void aReadInvocationCannotPromoteTheGenericWriteScope() {
        var ctx = context("read");
        invocation.execute(ctx);
        assembly.execute(ctx);
        assertThat(ctx.getPermitPlan().scope()).isEqualTo(CommandPermitPlan.ScopeGrade.SELF);
    }

    @Test void aLaterFailedInvocationCannotReuseAnEarlierWitness() {
        var ctx = context("update");
        invocation.execute(ctx);
        when(((CommandInvocationAuthorizer) handler).authorizeInvocation(any())).thenReturn(false);
        assertThatThrownBy(() -> invocation.execute(ctx)).isInstanceOf(AccessDeniedException.class);
        assembly.execute(ctx);
        assertThat(ctx.getPermitPlan().scope()).isNotEqualTo(CommandPermitPlan.ScopeGrade.TARGET);
    }

    private Map<String,Object> config(String action) {
        return Map.of("handler", "resource:hold", "handlerParams",
                Map.of("invocationAuthorization", "handler", "targetRecordAction", action));
    }
    private CommandPipelineContext context(String action) {
        var command = new CommandDefinition(); command.setCode("resource:hold"); command.setModelCode("anc_resource");
        var request = new CommandExecuteRequest(); request.setTargetRecordId("resource-1"); request.setPayload(Map.of());
        var ctx = CommandPipelineContext.builder().tenantId(7L).userId(9L).commandCode("resource:hold")
                .command(command).request(request).execConfig(config(action)).build();
        ctx.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("resource.hold"));
        ctx.setTargetRecordReadable(true); ctx.setTargetRecordVersion(7L);
        ctx.recordPhaseDecision(CommandPermitPlan.PhaseDecision.permit("authorization"));
        return ctx;
    }
}
