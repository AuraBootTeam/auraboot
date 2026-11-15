package com.auraboot.framework.meta.controller;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.synthesized.DynamicWriteCommandSeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dual-path routing for the dynamic write surface (owner decision 2026-11-15:
 * synthesized-dynamic-writes defaults to ON in production wiring). The flag-on
 * path routes through the command pipeline (seed + executor) and must NOT
 * touch the direct service; the flag-off rollback path does the opposite.
 * Plain unit construction leaves the boolean field at false (no Spring @Value),
 * which also documents why the pre-existing controller suites keep testing
 * the direct path.
 */
@ExtendWith(MockitoExtension.class)
class DynamicControllerSynthesizedRoutingTest {

    @Mock
    private DynamicDataService dynamicDataService;
    @Mock
    private CommandExecutor synthesizedCommandExecutor;
    @Mock
    private DynamicWriteCommandSeedService dynamicWriteCommandSeedService;
    @Mock
    private com.auraboot.framework.meta.service.ModelFieldBindingService modelFieldBindingService;
    @Mock
    private com.auraboot.framework.meta.service.MetaModelService metaModelService;

    private DynamicController controller;

    @BeforeEach
    void setUp() {
        controller = new DynamicController();
        ReflectionTestUtils.setField(controller, "dynamicDataService", dynamicDataService);
        ReflectionTestUtils.setField(controller, "modelFieldBindingService", modelFieldBindingService);
        ReflectionTestUtils.setField(controller, "metaModelService", metaModelService);
    }

    private void wireSynthesizedPath() {
        ReflectionTestUtils.setField(controller, "synthesizedCommandExecutor", synthesizedCommandExecutor);
        ReflectionTestUtils.setField(controller, "dynamicWriteCommandSeedService", dynamicWriteCommandSeedService);
        ReflectionTestUtils.setField(controller, "synthesizedDynamicWrites", true);
    }

    @Test
    void flagOn_routesThroughPipelineAndNeverTouchesDirectService() {
        wireSynthesizedPath();
        when(metaModelService.getModelDefinition(anyString())).thenReturn(java.util.Optional.of(com.auraboot.framework.meta.dto.ModelDefinition.builder().build()));
        
        when(dynamicWriteCommandSeedService.ensureSeeded(anyString())).thenReturn("dynamic.update");
        CommandExecuteResult result = CommandExecuteResult.builder().data(Map.of("ok", true)).build();
        when(synthesizedCommandExecutor.execute(eq("dynamic.update"), any(CommandExecuteRequest.class)))
                .thenReturn(result);

        var response = controller.update("demo_page", "rec-1",
                new java.util.HashMap<>(Map.of("name", "x")), null);

        verify(dynamicWriteCommandSeedService).ensureSeeded("dynamic.update");
        ArgumentCaptor<CommandExecuteRequest> captor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(synthesizedCommandExecutor).execute(eq("dynamic.update"), captor.capture());
        assertThat(captor.getValue().getPayload())
                .containsEntry("pageKey", "demo_page")
                .containsEntry("modelCode", "demo_page")
                .containsEntry("recordPid", "rec-1")
                .containsEntry("name", "x");
        assertThat(captor.getValue().getTargetRecordId()).isEqualTo("rec-1");
        verify(dynamicDataService, never()).update(anyString(), anyString(), any());
        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    void flagOff_rollbackPathUsesDirectServiceAndNeverSeeds() {
        // boolean field left at false — the explicit rollback switch
        when(metaModelService.getModelDefinition(anyString())).thenReturn(java.util.Optional.of(com.auraboot.framework.meta.dto.ModelDefinition.builder().build()));
        
        when(dynamicDataService.update(anyString(), anyString(), any())).thenReturn(Map.of("name", "x"));

        controller.update("demo_page", "rec-1", new java.util.HashMap<>(Map.of("name", "x")), null);

        verify(dynamicDataService).update(eq("demo_page"), eq("rec-1"), any());
        verify(dynamicWriteCommandSeedService, never()).ensureSeeded(anyString());
        verify(synthesizedCommandExecutor, never()).execute(anyString(), any());
    }

    @Test
    void flagOn_deleteRoutesWithRecordIdCarryingOperation() {
        wireSynthesizedPath();
        when(metaModelService.getModelDefinition(anyString())).thenReturn(java.util.Optional.of(com.auraboot.framework.meta.dto.ModelDefinition.builder().build()));
        
        when(dynamicWriteCommandSeedService.ensureSeeded(anyString())).thenReturn("dynamic.delete");
        when(synthesizedCommandExecutor.execute(eq("dynamic.delete"), any(CommandExecuteRequest.class)))
                .thenReturn(CommandExecuteResult.builder().build());

        controller.delete("demo_page", "rec-9");

        ArgumentCaptor<CommandExecuteRequest> captor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(synthesizedCommandExecutor).execute(eq("dynamic.delete"), captor.capture());
        assertThat(captor.getValue().getTargetRecordId()).isEqualTo("rec-9");
        assertThat(captor.getValue().getPayload()).containsEntry("recordPid", "rec-9");
        verify(dynamicDataService, never()).delete(anyString(), anyString());
    }
}
