package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.extension.BpmExtensionAccessor;
import com.auraboot.framework.bpm.event.EventBusService;
import com.auraboot.framework.bpm.service.BpmNodeHookService;
import com.auraboot.framework.bpm.service.BpmRuleBindingRuntimeService;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.smart.framework.engine.bpmn.assembly.task.UserTask;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.model.instance.ExecutionInstance;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.pvm.event.EventConstant;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessEventListenerTest {

    private ProcessEventListener listener(
            BpmAuditService auditService,
            EventBusService eventBusService,
            BpmNodeHookService hookService) {
        return listener(
                auditService,
                eventBusService,
                hookService,
                mock(BpmExtensionAccessor.class),
                mock(BpmRuleBindingRuntimeService.class));
    }

    private ProcessEventListener listener(
            BpmAuditService auditService,
            EventBusService eventBusService,
            BpmNodeHookService hookService,
            BpmExtensionAccessor extensionAccessor,
            BpmRuleBindingRuntimeService ruleBindingRuntimeService) {
        return new ProcessEventListener(
                auditService,
                eventBusService,
                hookService,
                extensionAccessor,
                ruleBindingRuntimeService);
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
    void activityStartSkipsGenericRuleBindingEvaluationForUserTaskAssignmentRules() {
        BpmAuditService auditService = mock(BpmAuditService.class);
        EventBusService eventBusService = mock(EventBusService.class);
        BpmNodeHookService hookService = mock(BpmNodeHookService.class);
        BpmExtensionAccessor extensionAccessor = mock(BpmExtensionAccessor.class);
        BpmRuleBindingRuntimeService ruleBindingRuntimeService = mock(BpmRuleBindingRuntimeService.class);
        ExecutionContext context = mock(ExecutionContext.class);
        ProcessInstance processInstance = mock(ProcessInstance.class);
        ExecutionInstance executionInstance = mock(ExecutionInstance.class);

        when(context.getRequest()).thenReturn(Map.of(
                RequestMapSpecialKeyConstant.TENANT_ID, "1",
                RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID, "42"));
        when(context.getProcessInstance()).thenReturn(processInstance);
        when(processInstance.getInstanceId()).thenReturn("pi-5");
        when(processInstance.getProcessDefinitionId()).thenReturn("proc-key");
        when(context.getExecutionInstance()).thenReturn(executionInstance);
        when(executionInstance.getProcessDefinitionActivityId()).thenReturn("task_manager_approve");
        when(context.getBaseElement()).thenReturn(new UserTask());
        when(hookService.executePreChecks(eq("proc-key"), eq("task_manager_approve"), anyMap()))
                .thenReturn(new BpmNodeHookService.HookExecutionResult(true, null));

        ProcessEventListener listener = listener(
                auditService,
                eventBusService,
                hookService,
                extensionAccessor,
                ruleBindingRuntimeService);

        listener.execute(EventConstant.ACTIVITY_START, context);

        verify(extensionAccessor, never()).getRuleConsumerBinding(anyString(), anyString());
        verify(ruleBindingRuntimeService, never()).evaluateAndApply(
                any(), anyString(), anyString(), anyString(), anyMap());
        verify(hookService).executePreChecks(eq("proc-key"), eq("task_manager_approve"), anyMap());
        verify(auditService).recordActivityEvent(
                eq("pi-5"), eq("task_manager_approve"), eq("activity_start"),
                anyString(), eq("42"), eq("1"));
    }

    @Test
    void activityStartStillEvaluatesRuleBindingForNonUserTaskNodes() {
        BpmAuditService auditService = mock(BpmAuditService.class);
        EventBusService eventBusService = mock(EventBusService.class);
        BpmNodeHookService hookService = mock(BpmNodeHookService.class);
        BpmExtensionAccessor extensionAccessor = mock(BpmExtensionAccessor.class);
        BpmRuleBindingRuntimeService ruleBindingRuntimeService = mock(BpmRuleBindingRuntimeService.class);
        RuleConsumerBinding binding = mock(RuleConsumerBinding.class);
        ExecutionContext context = mock(ExecutionContext.class);
        ProcessInstance processInstance = mock(ProcessInstance.class);
        ExecutionInstance executionInstance = mock(ExecutionInstance.class);

        when(context.getRequest()).thenReturn(Map.of(RequestMapSpecialKeyConstant.TENANT_ID, "1"));
        when(context.getProcessInstance()).thenReturn(processInstance);
        when(processInstance.getInstanceId()).thenReturn("pi-6");
        when(processInstance.getProcessDefinitionId()).thenReturn("proc-key");
        when(context.getExecutionInstance()).thenReturn(executionInstance);
        when(executionInstance.getProcessDefinitionActivityId()).thenReturn("gateway_approve");
        when(extensionAccessor.getRuleConsumerBinding("proc-key", "gateway_approve"))
                .thenReturn(Optional.of(binding));
        when(hookService.executePreChecks(eq("proc-key"), eq("gateway_approve"), anyMap()))
                .thenReturn(new BpmNodeHookService.HookExecutionResult(true, null));

        ProcessEventListener listener = listener(
                auditService,
                eventBusService,
                hookService,
                extensionAccessor,
                ruleBindingRuntimeService);

        listener.execute(EventConstant.ACTIVITY_START, context);

        verify(ruleBindingRuntimeService).evaluateAndApply(
                eq(binding), eq("proc-key"), eq("gateway_approve"), eq("pi-6"), anyMap());
        verify(hookService).executePreChecks(eq("proc-key"), eq("gateway_approve"), anyMap());
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
