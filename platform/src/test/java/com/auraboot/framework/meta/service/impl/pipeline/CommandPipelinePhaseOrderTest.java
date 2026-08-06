package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.meta.service.impl.pipeline.phases.AssertPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.AutoSetPhase;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CommandAuthorizationPhase;
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
            CommandTargetScopePhase.class,
            SchemaValidatePhase.class,
            EntitlementPhase.class,
            SodCheckPhase.class,
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
