package com.auraboot.framework.bpm.handler;

import com.auraboot.framework.bpm.service.BpmIntegrationService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BpmStartProcessHandler}. The handler is a thin wrapper over
 * {@link BpmIntegrationService#startBusinessProcess}; its contract — argument
 * propagation, default title, output shape, validation errors — is verified with
 * a Mockito collaborator rather than a full process deployment (the integration
 * of the underlying engine is already covered by ProcessEngineService tests).
 */
@ExtendWith(MockitoExtension.class)
class BpmStartProcessHandlerTest {

    @Mock
    private BpmIntegrationService bpmIntegrationService;

    @InjectMocks
    private BpmStartProcessHandler handler;

    @Test
    void getCommandType_returnsBpmStartProcess() {
        assertThat(handler.getCommandType()).isEqualTo(BpmStartProcessHandler.COMMAND_CODE);
    }

    @Test
    void execute_happyPath_returnsProcessInstanceId() {
        ProcessInstance instance = org.mockito.Mockito.mock(ProcessInstance.class);
        when(instance.getInstanceId()).thenReturn("proc-123");
        when(bpmIntegrationService.startBusinessProcess(
                eq("wd_leave_approval"), eq("rec-1"), any(), any()))
                .thenReturn(instance);

        Map<String, Object> payload = new HashMap<>();
        payload.put(BpmStartProcessHandler.ARG_PROCESS_KEY, "wd_leave_approval");
        payload.put(BpmStartProcessHandler.ARG_BUSINESS_KEY, "rec-1");
        payload.put(BpmStartProcessHandler.ARG_VARIABLES, Map.of("days", 3));
        payload.put(BpmStartProcessHandler.ARG_TITLE, "Leave for Alice");

        CommandContext ctx = CommandContext.builder()
                .commandType(BpmStartProcessHandler.COMMAND_CODE)
                .payload(payload)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.execute(ctx);
        assertThat(result).containsEntry(BpmStartProcessHandler.RESULT_PROCESS_INSTANCE_ID, "proc-123");

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(bpmIntegrationService).startBusinessProcess(
                eq("wd_leave_approval"), eq("rec-1"), any(), titleCaptor.capture());
        assertThat(titleCaptor.getValue()).isEqualTo("Leave for Alice");
    }

    @Test
    void execute_defaultTitle_whenAbsent() {
        ProcessInstance instance = org.mockito.Mockito.mock(ProcessInstance.class);
        when(instance.getInstanceId()).thenReturn("proc-xyz");
        when(bpmIntegrationService.startBusinessProcess(any(), any(), any(), any()))
                .thenReturn(instance);

        Map<String, Object> payload = Map.of(
                BpmStartProcessHandler.ARG_PROCESS_KEY, "wd_leave_approval",
                BpmStartProcessHandler.ARG_BUSINESS_KEY, "rec-9"
        );
        CommandContext ctx = CommandContext.builder()
                .commandType(BpmStartProcessHandler.COMMAND_CODE)
                .payload(payload)
                .build();

        handler.execute(ctx);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(bpmIntegrationService).startBusinessProcess(
                any(), any(), any(), titleCaptor.capture());
        assertThat(titleCaptor.getValue()).isEqualTo("wd_leave_approval-rec-9");
    }

    @Test
    void execute_missingProcessKey_throws() {
        CommandContext ctx = CommandContext.builder()
                .commandType(BpmStartProcessHandler.COMMAND_CODE)
                .payload(Map.of(BpmStartProcessHandler.ARG_BUSINESS_KEY, "x"))
                .build();
        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(BpmStartProcessHandler.ERR_PROCESS_KEY_REQUIRED);
    }

    @Test
    void execute_missingBusinessKey_throws() {
        CommandContext ctx = CommandContext.builder()
                .commandType(BpmStartProcessHandler.COMMAND_CODE)
                .payload(Map.of(BpmStartProcessHandler.ARG_PROCESS_KEY, "x"))
                .build();
        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(BpmStartProcessHandler.ERR_BUSINESS_KEY_REQUIRED);
    }

    @Test
    void execute_nullPayload_throws() {
        CommandContext ctx = CommandContext.builder()
                .commandType(BpmStartProcessHandler.COMMAND_CODE)
                .payload(null)
                .build();
        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class);
    }
}
