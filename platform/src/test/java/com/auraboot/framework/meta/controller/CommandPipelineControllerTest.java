package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.constant.CommandStage;
import com.auraboot.framework.meta.service.impl.CommandPhaseRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandPipelineControllerTest {

    @Mock
    private CommandPhaseRegistry pipelineRegistry;

    @InjectMocks
    private CommandPipelineController controller;

    @Test
    void getAllPhases_usesMetaRegistryShape() {
        CommandPhaseRegistry.PhaseHandlerDescriptor handler = new CommandPhaseRegistry.PhaseHandlerDescriptor(
                "schemaValidatePhase",
                "com.auraboot.framework.meta.SchemaValidatePhase",
                "Schema Validate",
                new int[] {CommandStage.SCHEMA_VALIDATE},
                true,
                true,
                "Validates command payload schema"
        );
        CommandPhaseRegistry.PhaseDescriptor phase = new CommandPhaseRegistry.PhaseDescriptor(
                CommandStage.SCHEMA_VALIDATE,
                CommandStage.nameOf(CommandStage.SCHEMA_VALIDATE),
                true,
                List.of(handler)
        );
        when(pipelineRegistry.getAllPhases()).thenReturn(List.of(phase));

        ApiResponse<List<Map<String, Object>>> response = controller.getAllPhases();

        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(1, response.getData().size());
        Map<String, Object> item = response.getData().get(0);
        assertEquals(CommandStage.SCHEMA_VALIDATE, item.get("stage"));
        assertEquals("schema_validate", item.get("name"));
        assertEquals("Basic payload schema validation", item.get("description"));
        assertEquals(true, item.get("interruptible"));
        assertEquals("INHERITED", item.get("transaction"));
        assertEquals(1, item.get("handlerCount"));
    }

    @Test
    void getPhase_returnsNotFoundForUnknownStage() {
        when(pipelineRegistry.getAllPhases()).thenReturn(List.of());

        ApiResponse<?> response = controller.getPhase(999);

        assertNotEquals("0", response.getCode());
        assertTrue(response.getMessage().contains("Phase not found"));
    }

    @Test
    void getSummary_aggregatesMetaRegistryCounts() {
        CommandPhaseRegistry.PhaseDescriptor transactionalPhase = new CommandPhaseRegistry.PhaseDescriptor(
                CommandStage.LOAD,
                CommandStage.nameOf(CommandStage.LOAD),
                true,
                List.of(
                        new CommandPhaseRegistry.PhaseHandlerDescriptor(
                                "loadPhase",
                                "LoadPhase",
                                "Load",
                                new int[] {CommandStage.LOAD},
                                true,
                                false,
                                "Loads command definition"
                        )
                )
        );
        CommandPhaseRegistry.PhaseDescriptor afterCommitPhase = new CommandPhaseRegistry.PhaseDescriptor(
                CommandStage.WEBHOOK,
                CommandStage.nameOf(CommandStage.WEBHOOK),
                false,
                List.of(
                        new CommandPhaseRegistry.PhaseHandlerDescriptor(
                                "webhookPhase",
                                "WebhookPhase",
                                "Webhook",
                                new int[] {CommandStage.WEBHOOK},
                                false,
                                false,
                                "Dispatches webhooks"
                        )
                )
        );
        when(pipelineRegistry.getAllPhases()).thenReturn(List.of(transactionalPhase, afterCommitPhase));
        when(pipelineRegistry.getAnnotatedStageCount()).thenReturn(2);

        ApiResponse<Map<String, Object>> response = controller.getSummary();

        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(2, response.getData().get("totalPhases"));
        assertEquals(2, response.getData().get("annotatedStages"));
        assertEquals(2, response.getData().get("totalHandlers"));
        assertEquals(CommandStage.TOTAL_TRANSACTIONAL_STAGES, response.getData().get("transactionalStages"));
        assertEquals(4, response.getData().get("afterCommitStages"));
    }
}
