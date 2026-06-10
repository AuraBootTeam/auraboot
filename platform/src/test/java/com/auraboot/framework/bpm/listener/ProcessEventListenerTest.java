package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.extension.BpmExtensionAccessor;
import com.auraboot.framework.bpm.event.EventBusService;
import com.auraboot.framework.bpm.service.BpmNodeHookService;
import com.auraboot.framework.bpm.service.BpmRuleBindingRuntimeService;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.model.instance.ExecutionInstance;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.pvm.event.EventConstant;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessEventListenerTest {

    private ProcessEventListener listener(
            BpmAuditService auditService,
            EventBusService eventBusService,
            BpmNodeHookService hookService) {
        return new ProcessEventListener(
                auditService,
                eventBusService,
                hookService,
                mock(BpmExtensionAccessor.class),
                mock(BpmRuleBindingRuntimeService.class));
    }

    @Test
    void rootProcessStartPublishesEventWithoutDuplicateProcessEventAudit() {
        BpmAuditService auditService = mock(BpmAuditService.class);
        EventBusService eventBusService = mock(EventBusService.class);
        BpmNodeHookService hookService = mock(BpmNodeHookService.class);
        ExecutionContext context = mock(ExecutionContext.class);
        ProcessInstance processInstance = mock(ProcessInstance.class);

        when(context.getRequest()).thenReturn(Map.of(
                RequestMapSpecialKeyConstant.TENANT_ID, "1",
                RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID, "42"
        ));
        when(context.getProcessInstance()).thenReturn(processInstance);
        when(processInstance.getInstanceId()).thenReturn("pi-1");

        ProcessEventListener listener = listener(auditService, eventBusService, hookService);

        listener.execute(EventConstant.PROCESS_START, context);

        verify(auditService, never()).recordProcessEvent(
                anyString(), eq("process_start"), anyString(), anyString(), anyString());
        verify(eventBusService).publishTransientProcessEvent(
                eq("process_started"), isNull(), eq("pi-1"), anyMap());
        verify(eventBusService, never()).publishProcessEvent(anyString(), any(), any(), anyMap());
    }

    @Test
    void activityEndRunsHooksAndRecordsActivityAuditWithoutUnkeyedActivityEvent() {
        BpmAuditService auditService = mock(BpmAuditService.class);
        EventBusService eventBusService = mock(EventBusService.class);
        BpmNodeHookService hookService = mock(BpmNodeHookService.class);
        ExecutionContext context = mock(ExecutionContext.class);
        ProcessInstance processInstance = mock(ProcessInstance.class);
        ExecutionInstance executionInstance = mock(ExecutionInstance.class);

        when(context.getRequest()).thenReturn(Map.of(
                RequestMapSpecialKeyConstant.TENANT_ID, "1"));
        when(context.getProcessInstance()).thenReturn(processInstance);
        when(processInstance.getInstanceId()).thenReturn("pi-2");
        when(processInstance.getProcessDefinitionId()).thenReturn("proc-key");
        when(context.getExecutionInstance()).thenReturn(executionInstance);
        when(executionInstance.getProcessDefinitionActivityId()).thenReturn("serviceTask1");

        ProcessEventListener listener = listener(auditService, eventBusService, hookService);

        listener.execute(EventConstant.ACTIVITY_END, context);

        verify(auditService).recordActivityEvent(
                eq("pi-2"), eq("serviceTask1"), eq("activity_end"),
                anyString(), isNull(), eq("1"));
        verify(hookService).executePostActions(eq("proc-key"), eq("serviceTask1"), anyMap());
        verify(eventBusService, never()).publishProcessEvent(
                eq("activity_completed"), any(), any(), anyMap());
    }

    @Test
    void processStartWithActivityIdDoesNotDuplicateActivityStartAudit() {
        BpmAuditService auditService = mock(BpmAuditService.class);
        EventBusService eventBusService = mock(EventBusService.class);
        BpmNodeHookService hookService = mock(BpmNodeHookService.class);
        ExecutionContext context = mock(ExecutionContext.class);
        ProcessInstance processInstance = mock(ProcessInstance.class);
        ExecutionInstance executionInstance = mock(ExecutionInstance.class);

        when(context.getRequest()).thenReturn(Map.of(
                RequestMapSpecialKeyConstant.TENANT_ID, "1",
                RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID, "42"));
        when(context.getProcessInstance()).thenReturn(processInstance);
        when(processInstance.getInstanceId()).thenReturn("pi-3");
        when(context.getExecutionInstance()).thenReturn(executionInstance);
        when(executionInstance.getProcessDefinitionActivityId()).thenReturn("serviceTask1");

        ProcessEventListener listener = listener(auditService, eventBusService, hookService);

        listener.execute(EventConstant.PROCESS_START, context);

        verify(auditService, never()).recordActivityEvent(
                anyString(), anyString(), eq("activity_start"), anyString(), any(), anyString());
        verify(eventBusService, never()).publishProcessEvent(anyString(), any(), any(), anyMap());
    }

    @Test
    void rootProcessEndRecordsAuditAndDispatchesTransientEventWithoutEventLogPersistence() {
        BpmAuditService auditService = mock(BpmAuditService.class);
        EventBusService eventBusService = mock(EventBusService.class);
        BpmNodeHookService hookService = mock(BpmNodeHookService.class);
        ExecutionContext context = mock(ExecutionContext.class);
        ProcessInstance processInstance = mock(ProcessInstance.class);

        when(context.getRequest()).thenReturn(Map.of(RequestMapSpecialKeyConstant.TENANT_ID, "1"));
        when(context.getProcessInstance()).thenReturn(processInstance);
        when(processInstance.getInstanceId()).thenReturn("pi-4");

        ProcessEventListener listener = listener(auditService, eventBusService, hookService);

        listener.execute(EventConstant.PROCESS_END, context);

        verify(auditService).recordProcessEvent(eq("pi-4"), eq("process_end"), anyString(), any(), eq("1"));
        verify(eventBusService).publishTransientProcessEvent(
                eq("process_ended"), isNull(), eq("pi-4"), anyMap());
        verify(eventBusService, never()).publishProcessEvent(anyString(), any(), any(), anyMap());
    }
}
