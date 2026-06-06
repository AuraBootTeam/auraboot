package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.bpm.service.BpmIntegrationService;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StartProcessActionExecutor (golden FINDING — start_process executor gap).
 */
@ExtendWith(MockitoExtension.class)
class StartProcessActionExecutorTest {

    @Mock
    private BpmIntegrationService bpmIntegrationService;

    @InjectMocks
    private StartProcessActionExecutor executor;

    @Test
    void supports_startProcess_returnsTrue() {
        assertThat(executor.supports("start_process")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(executor.supports("execute_command")).isFalse();
        assertThat(executor.supports("create_record")).isFalse();
    }

    @Test
    void execute_startsProcessWithResolvedBusinessKeyAndVariables() {
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getInstanceId()).thenReturn("PI-123");
        when(bpmIntegrationService.startBusinessProcess(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn(instance);

        AutomationAction action = new AutomationAction();
        Map<String, Object> config = new HashMap<>();
        config.put("processKey", "e2et_payment_approval");
        config.put("businessKey", "${recordId}");
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", "${record.amount}");
        vars.put("literal", "x");
        config.put("variables", vars);
        action.setConfig(config);

        Map<String, Object> context = new HashMap<>();
        context.put("recordId", "ORD-1");
        context.put("record", Map.of("amount", "500"));

        Object out = executor.execute(action, context);

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bizCap = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> varsCap = ArgumentCaptor.forClass(Map.class);
        verify(bpmIntegrationService).startBusinessProcess(keyCap.capture(), bizCap.capture(), varsCap.capture(), anyString());

        assertThat(keyCap.getValue()).isEqualTo("e2et_payment_approval");
        assertThat(bizCap.getValue()).isEqualTo("ORD-1"); // ${recordId} resolved
        assertThat(varsCap.getValue()).containsEntry("amount", "500"); // ${record.amount} resolved
        assertThat(varsCap.getValue()).containsEntry("literal", "x");

        assertThat(out).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) out;
        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("processInstanceId", "PI-123");
    }

    @Test
    void execute_defaultsBusinessKeyToRecordId_whenNotConfigured() {
        when(bpmIntegrationService.startBusinessProcess(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn(null);

        AutomationAction action = new AutomationAction();
        Map<String, Object> config = new HashMap<>();
        config.put("processKey", "p1");
        action.setConfig(config);

        Map<String, Object> context = new HashMap<>();
        context.put("recordId", "REC-9");

        executor.execute(action, context);

        verify(bpmIntegrationService).startBusinessProcess(eq("p1"), eq("REC-9"), anyMap(), anyString());
    }

    @Test
    void execute_missingProcessKey_throws() {
        AutomationAction action = new AutomationAction();
        action.setConfig(new HashMap<>());
        assertThatThrownBy(() -> executor.execute(action, new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processKey");
    }

    @Test
    void execute_nullConfig_throws() {
        AutomationAction action = new AutomationAction();
        action.setConfig(null);
        assertThatThrownBy(() -> executor.execute(action, new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
