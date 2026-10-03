package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.handler.TenantMemberCommandHandler;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.organization.service.OrgEmployeeService;
import com.auraboot.framework.tenant.service.TenantMemberApplicationService;
import com.auraboot.framework.meta.service.MetaModelService;
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
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private ApplicationContext applicationContext;

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject", "suspend", "restore", "leave", "delete"})
    void nativeMemberHandlerOwnsMutationBeforeOffboardingChecks(String action) {
        TenantMemberCommandHandler handler = new TenantMemberCommandHandler(
                mock(TenantMemberApplicationService.class), mock(OrgEmployeeService.class));
        CommandPipelineContext ctx = handlerContext("admin:" + action + "_member",
                "delete".equals(action) ? "delete" : "state_transition");
        when(applicationContext.getBean("tenantMemberCommandHandler", CommandHandler.class)).thenReturn(handler);

        phase().execute(ctx);

        verifyNoInteractions(fieldMapExecutor, cascadeDeleteExecutor);
        assertThat(ctx.getFieldMapResults()).isEmpty();
    }

    @Test
    void pluginOwnedDeleteAlsoRetainsChildrenUntilItsHandlerRuns() {
        CommandPipelineContext ctx = handlerContext("test:domain_delete", "delete");
        when(extensionRegistry.getCommandHandler(ctx.getCommandCode())).thenReturn(Optional.of(pluginHandler));
        when(pluginHandler.requiresDslPersistence(ctx.getCommandCode(), ctx.getExecConfig(), ctx.getRequest()))
                .thenReturn(false);

        phase().execute(ctx);

        verifyNoInteractions(fieldMapExecutor, cascadeDeleteExecutor, applicationContext);
        assertThat(ctx.getFieldMapResults()).isEmpty();
    }

    @Test
    void ordinarySpringHandlerRetainsImplicitPersistence() {
        CommandPipelineContext ctx = handlerContext("test:ordinary_delete", "delete");
        CommandHandler handler = mock(CommandHandler.class);
        when(applicationContext.getBean("tenantMemberCommandHandler", CommandHandler.class)).thenReturn(handler);
        when(handler.requiresDslPersistence(ctx.getCommandCode(), ctx.getExecConfig(), ctx.getRequest()))
                .thenReturn(true);

        phase().execute(ctx);

        verify(cascadeDeleteExecutor).executeCascadeDeletePhase(ctx.getExecConfig(), 1L, ctx.getRequest());
        verify(fieldMapExecutor).executeImplicitFieldMapPhase(
                ctx.getExecConfig(), ctx.getPayload(), 1L, ctx.getRequest(), ctx.getCommand());
    }

    @Test
    void resolvesDeclaredHandlerNameWhenItDiffersFromBeanName() {
        CommandPipelineContext ctx = handlerContext("admin:delete_member", "delete");
        TenantMemberCommandHandler handler = new TenantMemberCommandHandler(
                mock(TenantMemberApplicationService.class), mock(OrgEmployeeService.class));
        when(applicationContext.getBean("tenantMemberCommandHandler", CommandHandler.class))
                .thenThrow(new NoSuchBeanDefinitionException("tenantMemberCommandHandler"));
        when(applicationContext.getBeansOfType(CommandHandler.class)).thenReturn(Map.of("differentBeanName", handler));

        phase().execute(ctx);

        verifyNoInteractions(fieldMapExecutor, cascadeDeleteExecutor);
        assertThat(ctx.getFieldMapResults()).isEmpty();
    }

    @Test
    void missingDeclaredHandlerFailsBeforeAnyGenericDelete() {
        CommandPipelineContext ctx = handlerContext("admin:delete_member", "delete");
        ctx.getRulesByType().get("handler").getFirst().setHandlerClass("missingHandler");
        when(applicationContext.getBean("missingHandler", CommandHandler.class))
                .thenThrow(new NoSuchBeanDefinitionException("missingHandler"));
        when(applicationContext.getBeansOfType(CommandHandler.class)).thenReturn(Map.of());

        assertThatThrownBy(() -> phase().execute(ctx)).isInstanceOf(NoSuchBeanDefinitionException.class);

        verifyNoInteractions(fieldMapExecutor, cascadeDeleteExecutor);
    }

    private FieldMapPhase phase() {
        return new FieldMapPhase(fieldMapExecutor, cascadeDeleteExecutor, snapshotReader,
                extensionRegistry, metaModelService, applicationContext);
    }

    private CommandPipelineContext handlerContext(String code, String type) {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType(type);
        request.setTargetRecordId("member-target");
        CommandDefinition command = new CommandDefinition();
        command.setCode(code);
        command.setModelCode("tenant_member");
        BindingRule rule = new BindingRule();
        rule.setHandlerClass("tenantMemberCommandHandler");
        return CommandPipelineContext.builder().commandCode(code).request(request)
                .command(command).tenantId(1L).userId(2L).startTime(System.currentTimeMillis())
                .payload(new HashMap<>()).execConfig(new HashMap<>(Map.of("type", type)))
                .rulesByType(Map.of("handler", List.of(rule))).build();
    }

    @Test
    void executeSkipsImplicitStateTransitionWhenPluginHandlerDisablesDslPersistence() {
        FieldMapPhase phase = new FieldMapPhase(
                fieldMapExecutor, cascadeDeleteExecutor, snapshotReader, extensionRegistry, metaModelService, applicationContext);

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

    /**
     * Regression: `type: "delete"` commands invoked without an explicit
     * {@code request.operationType="delete"} (typical CLI / API flow with
     * {@code --target <pid>} only) must still route to the implicit
     * field-map path so the DELETE SQL fires. Before the fix the routing
     * fell through to the explicit {@code executeFieldMapPhase} branch
     * with empty binding rules, producing a silent no-op while the
     * pipeline reported {@code phaseReached=completed}.
     */
    @Test
    void deleteCommandWithoutOperationTypeStillRoutesToImplicitFieldMap() {
        FieldMapPhase phase = new FieldMapPhase(
                fieldMapExecutor, cascadeDeleteExecutor, snapshotReader, extensionRegistry, metaModelService, applicationContext);

        CommandExecuteRequest request = new CommandExecuteRequest();
        // operationType deliberately not set — the CLI/API flow we're regressing
        request.setTargetRecordId("rule-pid-42");

        CommandDefinition command = new CommandDefinition();
        command.setCode("acs:delete_safety_rule");
        command.setModelCode("acs_safety_rule");

        Map<String, Object> execConfig = new HashMap<>(Map.of("type", "delete"));

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode(command.getCode())
                .request(request)
                .tenantId(1L)
                .userId(2L)
                .startTime(System.currentTimeMillis())
                .command(command)
                .payload(new HashMap<>())
                .execConfig(execConfig)
                .rulesByType(new HashMap<>()) // no field_map binding rules
                .build();

        when(extensionRegistry.getCommandHandler("acs:delete_safety_rule")).thenReturn(Optional.empty());
        when(metaModelService.getModelDefinition("acs_safety_rule")).thenReturn(Optional.empty());
        when(fieldMapExecutor.executeImplicitFieldMapPhase(
                same(execConfig), same(ctx.getPayload()), eq(1L), same(request), same(command)))
                .thenReturn(Map.of("acs_safety_rule_deleted", 1));

        phase.execute(ctx);

        // Non-soft-delete model → physical cascade delete runs.
        verify(cascadeDeleteExecutor).executeCascadeDeletePhase(same(execConfig), eq(1L), same(request));
        verify(fieldMapExecutor).executeImplicitFieldMapPhase(
                same(execConfig), same(ctx.getPayload()), eq(1L), same(request), same(command));
        assertThat(ctx.getFieldMapResults()).containsEntry("acs_safety_rule_deleted", 1);
    }

    /**
     * Soft-delete model: the parent is only flagged deleted, so the physical
     * cascade-delete of children must be skipped (children survive / stay
     * recoverable). The flag-update itself happens inside the implicit field-map
     * executor (CommandFieldMapExecutor's soft/hard delete split).
     */
    @Test
    void deleteOfSoftDeleteModel_skipsPhysicalCascadeDelete() {
        FieldMapPhase phase = new FieldMapPhase(
                fieldMapExecutor, cascadeDeleteExecutor, snapshotReader, extensionRegistry, metaModelService, applicationContext);

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("delete");
        request.setTargetRecordId("quote-pid-7");

        CommandDefinition command = new CommandDefinition();
        command.setCode("qo_quote_common:delete");
        command.setModelCode("qo_quote_common");

        Map<String, Object> execConfig = new HashMap<>(Map.of("type", "delete"));

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode(command.getCode())
                .request(request)
                .tenantId(1L)
                .userId(2L)
                .startTime(System.currentTimeMillis())
                .command(command)
                .payload(new HashMap<>())
                .execConfig(execConfig)
                .rulesByType(new HashMap<>())
                .build();

        when(metaModelService.getModelDefinition("qo_quote_common"))
                .thenReturn(Optional.of(ModelDefinition.builder().softDelete(true).build()));
        when(extensionRegistry.getCommandHandler("qo_quote_common:delete")).thenReturn(Optional.empty());
        when(fieldMapExecutor.executeImplicitFieldMapPhase(
                same(execConfig), same(ctx.getPayload()), eq(1L), same(request), same(command)))
                .thenReturn(Map.of("qo_quote_common_deleted", 1));

        phase.execute(ctx);

        verify(cascadeDeleteExecutor, never())
                .executeCascadeDeletePhase(same(execConfig), eq(1L), same(request));
        verify(fieldMapExecutor).executeImplicitFieldMapPhase(
                same(execConfig), same(ctx.getPayload()), eq(1L), same(request), same(command));
    }
}
