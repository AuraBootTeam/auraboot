package com.auraboot.framework.automation.controller;

import com.auraboot.framework.automation.dto.AutomationLogDTO;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.common.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AutomationController pid-only public contract")
class AutomationControllerPidContractTest {

    @Mock
    private AutomationService automationService;

    @Test
    void triggerManuallyUsesRecordPidRequestField() {
        AutomationController controller = new AutomationController(automationService);
        AutomationLogDTO log = AutomationLogDTO.builder()
                .pid("log-pid")
                .build();
        when(automationService.triggerManually(
                eq("auto-pid"),
                eq("record-pid-1"),
                ArgumentMatchers.<Map<String, Object>>eq(Map.of()))).thenReturn(log);

        ApiResponse<AutomationLogDTO> response = controller.triggerManually(
                "auto-pid",
                Map.of("recordPid", "record-pid-1"));

        assertThat(response.getData()).isSameAs(log);
        verify(automationService).triggerManually("auto-pid", "record-pid-1", Map.of());
    }

    @Test
    void triggerManuallyPassesExplicitContextToService() {
        AutomationController controller = new AutomationController(automationService);
        AutomationLogDTO log = AutomationLogDTO.builder()
                .pid("log-pid")
                .build();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("wd_req_days", 5);
        Map<String, Object> context = Map.of("record", record);
        when(automationService.triggerManually("auto-pid", "record-pid-1", context)).thenReturn(log);

        ApiResponse<AutomationLogDTO> response = controller.triggerManually(
                "auto-pid",
                Map.of(
                        "recordPid", "record-pid-1",
                        "context", context));

        assertThat(response.getData()).isSameAs(log);
        verify(automationService).triggerManually("auto-pid", "record-pid-1", context);
    }

    @Test
    void triggerManuallyIgnoresLegacyRecordIdField() {
        AutomationController controller = new AutomationController(automationService);
        AutomationLogDTO log = AutomationLogDTO.builder()
                .pid("log-pid")
                .build();
        when(automationService.triggerManually(
                eq("auto-pid"),
                eq(null),
                ArgumentMatchers.<Map<String, Object>>eq(Map.of()))).thenReturn(log);

        controller.triggerManually("auto-pid", Map.of("record" + "Id", "legacy-record-id"));

        ArgumentCaptor<String> recordPidCaptor = ArgumentCaptor.forClass(String.class);
        verify(automationService).triggerManually(
                eq("auto-pid"),
                recordPidCaptor.capture(),
                ArgumentMatchers.<Map<String, Object>>eq(Map.of()));
        assertThat(recordPidCaptor.getValue()).isNull();
    }
}
