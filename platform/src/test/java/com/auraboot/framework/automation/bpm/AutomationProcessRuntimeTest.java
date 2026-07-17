package com.auraboot.framework.automation.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomationProcessRuntimeTest {

    @AfterEach
    void clearMetaContext() {
        MetaContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_threadsRestrictedAutomationActorContextIntoSmartEngineVariables() {
        ObjectMapper objectMapper = new ObjectMapper();
        AutomationFlowCompiler compiler = mock(AutomationFlowCompiler.class);
        ProcessDeploymentService deploymentService = mock(ProcessDeploymentService.class);
        ProcessEngineService processEngineService = mock(ProcessEngineService.class);
        AutomationProcessRuntime runtime = new AutomationProcessRuntime(
                compiler, deploymentService, processEngineService, objectMapper);

        Automation automation = new Automation();
        automation.setPid("AUTOCTX1");
        automation.setTenantId(7L);
        automation.setCreatedBy("user-11");
        automation.setModelCode("e2et_order");
        when(compiler.compile(automation)).thenReturn(new AutomationFlowCompiler.CompiledFlow(
                "auto_AUTOCTX1",
                objectMapper.createObjectNode(),
                Map.of("a1", Map.of("type", "update_record", "config", Map.of()))));

        MetaContext.setContext(7L, 11L, "user-11", "Automation Owner");
        MetaContext.setMemberId(22L);

        runtime.run(automation, "rec-1", Map.of("event", "update"));

        ArgumentCaptor<Map<String, Object>> variablesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(processEngineService).startProcess(eq("auto_AUTOCTX1"), eq("rec-1"), variablesCaptor.capture());
        Map<String, Object> variables = variablesCaptor.getValue();
        assertThat(variables)
                .containsEntry(AutomationActionServiceTaskDelegate.TENANT_ID_VAR, 7L)
                .containsEntry(AutomationActionServiceTaskDelegate.USER_ID_VAR, 0L)
                .containsEntry(AutomationActionServiceTaskDelegate.USER_PID_VAR, "automation:AUTOCTX1")
                .containsEntry(AutomationActionServiceTaskDelegate.USERNAME_VAR, "automation")
                .containsEntry("recordPid", "rec-1")
                .containsEntry("recordId", "rec-1")
                .containsEntry("modelCode", "e2et_order");
        assertThat(variables).doesNotContainKey(AutomationActionServiceTaskDelegate.MEMBER_ID_VAR);
        assertThat(variables.get("trigger")).isInstanceOf(Map.class);
    }
}
