package com.auraboot.framework.base.service.impl;

import com.auraboot.framework.base.annotation.CommandPhase;
import com.auraboot.framework.base.constant.CommandStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CommandPipelineRegistry.
 * Verifies phase scanning, handler registration, and query APIs.
 */
class CommandPipelineRegistryTest {

    private CommandPipelineRegistry registry;
    private ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        applicationContext = mock(ApplicationContext.class);
        registry = new CommandPipelineRegistry(applicationContext);
    }

    @Test
    void shouldInitializeAllCanonicalPhases_whenNoAnnotatedBeans() {
        // Given: no annotated beans
        when(applicationContext.getBeansWithAnnotation(CommandPhase.class))
                .thenReturn(Map.of());

        // When
        registry.init();

        // Then: all 24 canonical phases exist
        List<CommandPipelineRegistry.PhaseDefinition> phases = registry.getAllPhases();
        assertThat(phases).hasSize(24);

        // Verify first and last transactional phases
        assertThat(phases.get(0).stage()).isEqualTo(1);
        assertThat(phases.get(0).name()).isEqualTo("load");
        assertThat(phases.get(19).stage()).isEqualTo(20);
        assertThat(phases.get(19).name()).isEqualTo("post_invariant");

        // Verify after-commit phases
        assertThat(phases.get(20).stage()).isEqualTo(21);
        assertThat(phases.get(20).name()).isEqualTo("domain_event");
        assertThat(phases.get(23).stage()).isEqualTo(24);
        assertThat(phases.get(23).name()).isEqualTo("governance_snapshot");
    }

    @Test
    void shouldReturnPhasesInOrder() {
        when(applicationContext.getBeansWithAnnotation(CommandPhase.class))
                .thenReturn(Map.of());

        registry.init();

        List<CommandPipelineRegistry.PhaseDefinition> phases = registry.getAllPhases();
        for (int i = 0; i < phases.size() - 1; i++) {
            assertThat(phases.get(i).stage()).isLessThan(phases.get(i + 1).stage());
        }
    }

    @Test
    void shouldDetectAnnotatedBeans() {
        // Given: one annotated bean
        SamplePhaseHandler handler = new SamplePhaseHandler();
        when(applicationContext.getBeansWithAnnotation(CommandPhase.class))
                .thenReturn(Map.of("samplePhaseHandler", handler));

        // When
        registry.init();

        // Then: handler is registered at stage 6
        List<CommandPipelineRegistry.HandlerEntry> handlers = registry.getHandlersAtStage(CommandStage.STATE_CHECK);
        assertThat(handlers).hasSize(1);
        assertThat(handlers.get(0).beanName()).isEqualTo("samplePhaseHandler");
        assertThat(handlers.get(0).phaseName()).isEqualTo("State Validator");
        assertThat(handlers.get(0).interruptible()).isTrue();
        assertThat(handlers.get(0).transaction()).isEqualTo(CommandPhase.TransactionMode.INHERITED);
        assertThat(handlers.get(0).description()).isEqualTo("Validates state transitions");
    }

    @Test
    void shouldReturnEmptyHandlersForUnregisteredStage() {
        when(applicationContext.getBeansWithAnnotation(CommandPhase.class))
                .thenReturn(Map.of());

        registry.init();

        List<CommandPipelineRegistry.HandlerEntry> handlers = registry.getHandlersAtStage(CommandStage.LOAD);
        assertThat(handlers).isEmpty();
    }

    @Test
    void shouldGetPhaseByStageNumber() {
        when(applicationContext.getBeansWithAnnotation(CommandPhase.class))
                .thenReturn(Map.of());

        registry.init();

        Optional<CommandPipelineRegistry.PhaseDefinition> phase = registry.getPhase(CommandStage.HANDLER);
        assertThat(phase).isPresent();
        assertThat(phase.get().name()).isEqualTo("handler");
        assertThat(phase.get().description()).isEqualTo("Execute custom command handlers");
    }

    @Test
    void shouldReturnEmptyForUnknownStage() {
        when(applicationContext.getBeansWithAnnotation(CommandPhase.class))
                .thenReturn(Map.of());

        registry.init();

        Optional<CommandPipelineRegistry.PhaseDefinition> phase = registry.getPhase(999);
        assertThat(phase).isEmpty();
    }

    @Test
    void shouldExportPipelineWithHandlers() {
        SamplePhaseHandler handler = new SamplePhaseHandler();
        when(applicationContext.getBeansWithAnnotation(CommandPhase.class))
                .thenReturn(Map.of("samplePhaseHandler", handler));

        registry.init();

        List<Map<String, Object>> pipeline = registry.exportPipeline();
        assertThat(pipeline).hasSize(24);

        // Find stage 6 entry
        Map<String, Object> stateCheckEntry = pipeline.stream()
                .filter(e -> (int) e.get("stage") == CommandStage.STATE_CHECK)
                .findFirst()
                .orElseThrow();

        assertThat(stateCheckEntry.get("name")).isEqualTo("state_check");
        assertThat(stateCheckEntry.get("handlerCount")).isEqualTo(1);
        assertThat(stateCheckEntry.get("transactional")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> handlers = (List<Map<String, Object>>) stateCheckEntry.get("handlers");
        assertThat(handlers).hasSize(1);
        assertThat(handlers.get(0).get("beanName")).isEqualTo("samplePhaseHandler");
    }

    @Test
    void shouldCountAnnotatedStages() {
        SamplePhaseHandler handler = new SamplePhaseHandler();
        when(applicationContext.getBeansWithAnnotation(CommandPhase.class))
                .thenReturn(Map.of("samplePhaseHandler", handler));

        registry.init();

        assertThat(registry.getPhaseCount()).isEqualTo(24);
        assertThat(registry.getAnnotatedStageCount()).isEqualTo(1);
        assertThat(registry.getAllHandlers()).hasSize(1);
    }

    @Test
    void shouldSupportMultipleHandlersPerStage() {
        SamplePhaseHandler handler1 = new SamplePhaseHandler();
        AnotherStateHandler handler2 = new AnotherStateHandler();
        when(applicationContext.getBeansWithAnnotation(CommandPhase.class))
                .thenReturn(Map.of(
                        "samplePhaseHandler", handler1,
                        "anotherStateHandler", handler2
                ));

        registry.init();

        List<CommandPipelineRegistry.HandlerEntry> handlers = registry.getHandlersAtStage(CommandStage.STATE_CHECK);
        assertThat(handlers).hasSize(2);
    }

    @Test
    void shouldMarkAfterCommitPhasesAsNotSupported() {
        when(applicationContext.getBeansWithAnnotation(CommandPhase.class))
                .thenReturn(Map.of());

        registry.init();

        Optional<CommandPipelineRegistry.PhaseDefinition> domainEvent = registry.getPhase(CommandStage.DOMAIN_EVENT);
        assertThat(domainEvent).isPresent();
        assertThat(domainEvent.get().transaction()).isEqualTo(CommandPhase.TransactionMode.NOT_SUPPORTED);

        Optional<CommandPipelineRegistry.PhaseDefinition> webhook = registry.getPhase(CommandStage.WEBHOOK);
        assertThat(webhook).isPresent();
        assertThat(webhook.get().transaction()).isEqualTo(CommandPhase.TransactionMode.NOT_SUPPORTED);
    }

    @Test
    void shouldSetCorrectInterruptibility() {
        when(applicationContext.getBeansWithAnnotation(CommandPhase.class))
                .thenReturn(Map.of());

        registry.init();

        // Validation phases should be interruptible
        assertThat(registry.getPhase(CommandStage.SCHEMA_VALIDATE).get().interruptible()).isTrue();
        assertThat(registry.getPhase(CommandStage.ASSERT).get().interruptible()).isTrue();
        assertThat(registry.getPhase(CommandStage.HANDLER).get().interruptible()).isTrue();

        // Data-write phases should not be interruptible
        assertThat(registry.getPhase(CommandStage.LOAD).get().interruptible()).isFalse();
        assertThat(registry.getPhase(CommandStage.FIELD_MAP).get().interruptible()).isFalse();
        assertThat(registry.getPhase(CommandStage.EFFECT).get().interruptible()).isFalse();
    }

    // ==================== Test helpers ====================

    @CommandPhase(
            stage = CommandStage.STATE_CHECK,
            name = "State Validator",
            interruptible = true,
            description = "Validates state transitions"
    )
    static class SamplePhaseHandler {}

    @CommandPhase(
            stage = CommandStage.STATE_CHECK,
            name = "Another State Handler",
            interruptible = false,
            transaction = CommandPhase.TransactionMode.REQUIRES_NEW,
            description = "Additional state validation"
    )
    static class AnotherStateHandler {}
}
