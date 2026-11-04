package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.meta.service.impl.pipeline.phases.AssertPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CommandInputFieldAuthorizationPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.AutoSetPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CommandAuthorizationPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CommandInvocationAuthorizationPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CommandTargetScopePhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CommandTargetVersionLockPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CompletionPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.ComputedFieldsPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.EntitlementPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.FieldMapPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.HandlerPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.IdempotencyPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.LoadPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.PermitPlanAssemblyPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.PostExecutionPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.PreActionsPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.PreInvariantPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.SchemaValidatePhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.SodCheckPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CommandSubjectAcknowledgementPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.StateCheckPhase;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CommandPipelinePhaseOrderTest {

    /**
     * CommandPipelineConfiguration's List.of(...) remains the runtime authority.
     * These annotations are documentation consumed by readers and diagnostics, so
     * they must describe that same sequence without duplicates or reversals.
     */
    private static final List<Class<? extends CommandPhase>> CONFIGURED_ORDER = List.of(
            LoadPhase.class,
            CommandAuthorizationPhase.class,
            CommandInvocationAuthorizationPhase.class,
            CommandTargetScopePhase.class,
            SchemaValidatePhase.class,
            CommandInputFieldAuthorizationPhase.class,
            EntitlementPhase.class,
            SodCheckPhase.class,
            CommandSubjectAcknowledgementPhase.class,
            IdempotencyPhase.class,
            CommandTargetVersionLockPhase.class,
            PermitPlanAssemblyPhase.class,
            StateCheckPhase.class,
            AssertPhase.class,
            PreActionsPhase.class,
            PreInvariantPhase.class,
            AutoSetPhase.class,
            FieldMapPhase.class,
            ComputedFieldsPhase.class,
            HandlerPhase.class,
            PostExecutionPhase.class,
            CompletionPhase.class
    );

    @Test void everyProductionPhaseMustBeExplicitlyWired() {
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AssignableTypeFilter(CommandPhase.class));
        var discovered = scanner.findCandidateComponents("com.auraboot.framework.meta.service.impl.pipeline.phases")
                .stream().map(bean -> bean.getBeanClassName()).toList();
        assertThat(CONFIGURED_ORDER.stream().map(Class::getName).toList()).containsExactlyInAnyOrderElementsOf(discovered);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void forbiddenInputStopsTheConfiguredPipelineBeforeCachedOutcomeLookup(boolean completeGrantRestricted) throws Exception {
        var policies = org.mockito.Mockito.mock(com.auraboot.framework.meta.service.DataPermissionEngine.class);
        org.mockito.Mockito.when(policies.getNonWritableFields(7L,"orders",20L)).thenReturn(java.util.Set.of());
        var metadata = mock(com.auraboot.framework.meta.service.MetaModelService.class);
        var fields = mock(com.auraboot.framework.permission.service.FieldPermissionService.class);
        org.mockito.Mockito.when(metadata.getModelFields("orders")).thenReturn(java.util.List.of(
                com.auraboot.framework.meta.dto.FieldDefinition.builder().code("cost").columnName("cost").build()));
        org.mockito.Mockito.when(fields.getFieldPermissions(30L,"orders")).thenReturn(
                new com.auraboot.framework.permission.engine.model.FieldPermissionSet(java.util.Set.of("cost"),
                        completeGrantRestricted ? java.util.Set.of("cost") : java.util.Set.of(),java.util.Set.of()));
        com.auraboot.framework.application.tenant.MetaContext.setContext(7L,20L,"actor","Actor");
        com.auraboot.framework.application.tenant.MetaContext.setMemberId(30L);
        try {
            var instances = CONFIGURED_ORDER.stream().map(type -> type == CommandInputFieldAuthorizationPhase.class
                    ? (CommandPhase)new CommandInputFieldAuthorizationPhase(policies, metadata, fields) : (CommandPhase)mock(type)).toList();
            var factory = CommandPipelineConfiguration.class.getDeclaredMethod("commandPipeline", CONFIGURED_ORDER.toArray(Class[]::new));
            var pipeline = (CommandPipeline)factory.invoke(new CommandPipelineConfiguration(), instances.toArray());
            var command = new com.auraboot.framework.meta.entity.CommandDefinition(); command.setModelCode("orders");
            var context = CommandPipelineContext.builder().command(command).execConfig(java.util.Map.of("type","update")).payload(java.util.Map.of("cost",99)).build();
            if (completeGrantRestricted) {
                var request = new com.auraboot.framework.meta.dto.CommandExecuteRequest();request.setTargetRecordId("root");
                context = CommandPipelineContext.builder().command(command).request(request)
                        .execConfig(java.util.Map.of("type","update")).payload(java.util.Map.of("cost",99)).build();
                context.setBusinessGrantDecision(new com.auraboot.framework.permission.engine.model.BusinessGrantDecision(
                        com.auraboot.framework.permission.engine.model.PermissionResult.allow(java.util.List.of()),
                        7L,20L,30L,"orders","root",java.util.List.of(
                            new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Grant(1L,2L,"orders.update",
                                new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Fields(false,java.util.Set.of(),java.util.Set.of())))));
            }
            var checkedContext = context;
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> pipeline.executePreGuardPhases(checkedContext))
                    .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
            org.mockito.Mockito.verify(instances.get(CONFIGURED_ORDER.indexOf(IdempotencyPhase.class)),org.mockito.Mockito.never())
                    .execute(org.mockito.ArgumentMatchers.any());
        } finally { com.auraboot.framework.application.tenant.MetaContext.clear(); }
    }

    @Test
    void orderAnnotationsDescribeTheAuthoritativePipelineSequence() throws Exception {
        Method factory = CommandPipelineConfiguration.class.getDeclaredMethod(
                "commandPipeline",
                CONFIGURED_ORDER.toArray(Class[]::new)
        );
        List<CommandPhase> configuredInstances = CONFIGURED_ORDER.stream()
                .map(type -> (CommandPhase) mock(type))
                .toList();
        CommandPipeline pipeline = (CommandPipeline) factory.invoke(
                new CommandPipelineConfiguration(),
                configuredInstances.toArray()
        );

        List<CommandPhase> actualOrder = new ArrayList<>();
        actualOrder.addAll(readPhases(pipeline, "preGuardPhases"));
        actualOrder.addAll(readPhases(pipeline, "guardedPhases"));
        assertThat(actualOrder)
                .as("CommandPipelineConfiguration must keep the declared phase sequence")
                .containsExactlyElementsOf(configuredInstances);

        int previous = Integer.MIN_VALUE;

        for (Class<? extends CommandPhase> phase : CONFIGURED_ORDER) {
            Order annotation = phase.getAnnotation(Order.class);
            assertThat(annotation)
                    .as("%s must declare @Order", phase.getSimpleName())
                    .isNotNull();
            assertThat(annotation.value())
                    .as("%s must follow the previous configured phase", phase.getSimpleName())
                    .isGreaterThan(previous);
            previous = annotation.value();
        }

        assertThat(SchemaValidatePhase.class.getAnnotation(Order.class).value())
                .isEqualTo(275);
    }

    @SuppressWarnings("unchecked")
    private static List<CommandPhase> readPhases(CommandPipeline pipeline, String fieldName)
            throws ReflectiveOperationException {
        Field field = CommandPipeline.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (List<CommandPhase>) field.get(pipeline);
    }
}
