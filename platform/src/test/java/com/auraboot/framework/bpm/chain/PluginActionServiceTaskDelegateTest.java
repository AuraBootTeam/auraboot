package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.bpm.service.ExecutionLogService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.plugin.extension.ServiceTaskActionExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import com.auraboot.smart.framework.engine.model.assembly.ProcessDefinition;
import com.auraboot.smart.framework.engine.model.instance.ExecutionInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PluginActionServiceTaskDelegate}.
 */
@ExtendWith(MockitoExtension.class)
class PluginActionServiceTaskDelegateTest {

    @Mock
    private ExtensionRegistry extensionRegistry;

    @Mock
    private ServiceTaskActionExtension action;

    @Mock
    private ExecutionLogService executionLogService;

    @InjectMocks
    private PluginActionServiceTaskDelegate delegate;

    @Test
    @SuppressWarnings("unchecked")
    void execute_happyPath_invokesActionAndWritesResultVars() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_ACTION, "iot:recalibrate-sensor");
        props.put(BpmServiceTaskConstants.ATTR_RESULT_VAR, "calibResult");
        props.put("deviceId", "dev-1");

        Map<String, Object> vars = new HashMap<>();
        vars.put("deviceId", "dev-1");
        vars.put(RequestMapSpecialKeyConstant.PROCESS_BIZ_UNIQUE_ID, "REQ-CALIB-1");
        vars.put(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID, "starter-1");
        vars.put(RequestMapSpecialKeyConstant.TENANT_ID, "1001");

        Map<String, Object> result = Map.of("calibrated", true);
        when(extensionRegistry.getServiceTaskAction("iot:recalibrate-sensor")).thenReturn(Optional.of(action));
        when(action.execute(any())).thenReturn(result);

        ExecutionContext ctx = mockContext(props, vars, "recalibrate_node", "pi-action-success-1");
        delegate.execute(ctx);

        // ActionContext is built with the action type, live process vars and serviceTask properties
        ArgumentCaptor<ServiceTaskActionExtension.ActionContext> captor =
                ArgumentCaptor.forClass(ServiceTaskActionExtension.ActionContext.class);
        org.mockito.Mockito.verify(action).execute(captor.capture());
        ServiceTaskActionExtension.ActionContext passed = captor.getValue();
        assertThat(passed.actionType()).isEqualTo("iot:recalibrate-sensor");
        assertThat(passed.variables()).containsEntry("deviceId", "dev-1");
        assertThat(passed.properties()).containsEntry("deviceId", "dev-1");

        // result written to both the conventional and the named result variable
        assertThat(vars).containsEntry("_action_recalibrate_node_success", true);
        assertThat(vars).containsEntry("_action_recalibrate_node_result", result);
        assertThat(vars).containsEntry("calibResult", result);

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> outputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executionLogService).logActionExecuted(
                eq("pi-action-success-1"),
                eq("recalibrate_node"),
                inputCaptor.capture(),
                outputCaptor.capture(),
                any(Long.class));
        assertThat(inputCaptor.getValue())
                .containsEntry("actionType", "iot:recalibrate-sensor")
                .containsEntry("status", "SUCCESS")
                .containsEntry("resultVar", "calibResult")
                .containsEntry("processKey", "sms_process")
                .containsEntry("businessKey", "REQ-CALIB-1")
                .containsEntry("startUserId", "starter-1")
                .containsEntry("tenantId", "1001");
        Map<String, Object> actionOutput = (Map<String, Object>) outputCaptor.getValue().get("action");
        assertThat(actionOutput)
                .containsEntry("status", "SUCCESS")
                .containsEntry("actionType", "iot:recalibrate-sensor")
                .containsEntry("calibrated", true);
    }

    @Test
    void execute_missingAction_throws() {
        ExecutionContext ctx = mockContext(new HashMap<>(), new HashMap<>(), "node");
        assertThatThrownBy(() -> delegate.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(PluginActionServiceTaskDelegate.ERR_ACTION_REQUIRED);
    }

    @Test
    void execute_unresolvedAction_throws() {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_ACTION, "iot:nonexistent");
        when(extensionRegistry.getServiceTaskAction("iot:nonexistent")).thenReturn(Optional.empty());

        ExecutionContext ctx = mockContext(props, new HashMap<>(), "node");
        assertThatThrownBy(() -> delegate.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(PluginActionServiceTaskDelegate.ERR_ACTION_UNRESOLVED);
    }

    @Test
    void execute_extensionThrows_wrappedAsActionFailed() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_ACTION, "iot:boom");
        when(extensionRegistry.getServiceTaskAction("iot:boom")).thenReturn(Optional.of(action));
        when(action.execute(any())).thenThrow(new RuntimeException("device offline"));

        ExecutionContext ctx = mockContext(props, new HashMap<>(), "node");
        assertThatThrownBy(() -> delegate.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(PluginActionServiceTaskDelegate.ERR_ACTION_FAILED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_actionExecutionExceptionWritesStructuredFailureVars() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_ACTION, "SEND_SMS");
        props.put(BpmServiceTaskConstants.ATTR_RESULT_VAR, "smsResult");

        Map<String, Object> vars = new HashMap<>();
        vars.put(RequestMapSpecialKeyConstant.PROCESS_BIZ_UNIQUE_ID, "REQ-SMS-1");
        vars.put(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID, "starter-1");
        vars.put(RequestMapSpecialKeyConstant.TENANT_ID, "1001");
        when(extensionRegistry.getServiceTaskAction("SEND_SMS")).thenReturn(Optional.of(action));
        when(action.execute(any())).thenThrow(new ActionExecutionException(
                "No real SMS sender available",
                Map.of(
                        "channel", "sms",
                        "failureReason", "provider_unavailable",
                        "targetPhones", java.util.List.of("+8613800138000"),
                        "sentCount", 0),
                null));

        ExecutionContext ctx = mockContext(props, vars, "sms_node", "pi-sms-1");
        assertThatThrownBy(() -> delegate.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(PluginActionServiceTaskDelegate.ERR_ACTION_FAILED);

        assertThat(vars).containsEntry("_action_sms_node_success", false);
        assertThat(vars).containsEntry("_action_sms_node_error", "No real SMS sender available");
        assertThat(vars).containsKey("_action_sms_node_result");
        assertThat(vars).containsKey("smsResult");
        Map<String, Object> failure = (Map<String, Object>) vars.get("_action_sms_node_result");
        assertThat(vars.get("smsResult")).isEqualTo(failure);
        assertThat(failure)
                .containsEntry("status", "FAILED")
                .containsEntry("actionType", "SEND_SMS")
                .containsEntry("channel", "sms")
                .containsEntry("failureReason", "provider_unavailable")
                .containsEntry("sentCount", 0)
                .containsEntry("error", "No real SMS sender available");
        assertThat((java.util.List<String>) failure.get("targetPhones")).containsExactly("+8613800138000");

        ArgumentCaptor<Map<String, Object>> logContextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executionLogService).logNodeFailure(
                eq("pi-sms-1"),
                eq("sms_node"),
                any(ActionExecutionException.class),
                logContextCaptor.capture());
        assertThat(logContextCaptor.getValue())
                .containsEntry("actionType", "SEND_SMS")
                .containsEntry("status", "FAILED")
                .containsEntry("resultVar", "smsResult")
                .containsEntry("processKey", "sms_process")
                .containsEntry("businessKey", "REQ-SMS-1")
                .containsEntry("startUserId", "starter-1")
                .containsEntry("tenantId", "1001");
        assertThat(logContextCaptor.getValue().get("action")).isEqualTo(failure);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_modernActionExecutionExceptionPreservesMessageTaskFailurePayload() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_ACTION, "CREATE_TASK");
        props.put(BpmServiceTaskConstants.ATTR_RESULT_VAR, "taskResult");

        Map<String, Object> vars = new HashMap<>();
        vars.put(RequestMapSpecialKeyConstant.PROCESS_BIZ_UNIQUE_ID, "REQ-TASK-1");
        vars.put(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID, "starter-1");
        vars.put(RequestMapSpecialKeyConstant.TENANT_ID, "1001");
        when(extensionRegistry.getServiceTaskAction("CREATE_TASK")).thenReturn(Optional.of(action));
        when(action.execute(any())).thenThrow(new ActionExecutionException(
                "CREATE_TASK invalid target: abc",
                Map.of(
                        "delivery", "inbox",
                        "itemType", "task",
                        "failureReason", "target_invalid",
                        "targetType", "USER",
                        "target", "abc",
                        "invalidTarget", "abc",
                        "field", "payload.assignee",
                        "requiredContext", List.of("payload.assignee", "action.target"),
                        "modelCode", "wd_leave_request",
                        "recordPid", "REQ-TASK-1"),
                null));

        ExecutionContext ctx = mockContext(props, vars, "create_task_node", "pi-task-1");
        assertThatThrownBy(() -> delegate.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(PluginActionServiceTaskDelegate.ERR_ACTION_FAILED);

        Map<String, Object> failure = (Map<String, Object>) vars.get("_action_create_task_node_result");
        assertThat(vars.get("taskResult")).isEqualTo(failure);
        assertThat(failure)
                .containsEntry("status", "FAILED")
                .containsEntry("actionType", "CREATE_TASK")
                .containsEntry("delivery", "inbox")
                .containsEntry("itemType", "task")
                .containsEntry("failureReason", "target_invalid")
                .containsEntry("targetType", "USER")
                .containsEntry("target", "abc")
                .containsEntry("invalidTarget", "abc")
                .containsEntry("field", "payload.assignee")
                .containsEntry("modelCode", "wd_leave_request")
                .containsEntry("recordPid", "REQ-TASK-1")
                .containsEntry("error", "CREATE_TASK invalid target: abc");
        assertThat((List<String>) failure.get("requiredContext"))
                .containsExactly("payload.assignee", "action.target");

        ArgumentCaptor<Map<String, Object>> logContextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executionLogService).logNodeFailure(
                eq("pi-task-1"),
                eq("create_task_node"),
                any(ActionExecutionException.class),
                logContextCaptor.capture());
        assertThat(logContextCaptor.getValue())
                .containsEntry("actionType", "CREATE_TASK")
                .containsEntry("status", "FAILED")
                .containsEntry("resultVar", "taskResult")
                .containsEntry("businessKey", "REQ-TASK-1");
        assertThat(logContextCaptor.getValue().get("action")).isEqualTo(failure);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_actionFailureLogErrorDoesNotHideOriginalFailureVars() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_ACTION, "SEND_SMS");

        Map<String, Object> vars = new HashMap<>();
        when(extensionRegistry.getServiceTaskAction("SEND_SMS")).thenReturn(Optional.of(action));
        when(action.execute(any())).thenThrow(new ActionExecutionException(
                "No real SMS sender available",
                Map.of("channel", "sms", "failureReason", "provider_unavailable", "sentCount", 0),
                null));
        org.mockito.Mockito.doThrow(new IllegalStateException("MetaContext not initialized"))
                .when(executionLogService)
                .logNodeFailure(eq("pi-sms-log-fail"), eq("sms_node"), any(ActionExecutionException.class), any());

        ExecutionContext ctx = mockContext(props, vars, "sms_node", "pi-sms-log-fail");
        assertThatThrownBy(() -> delegate.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(PluginActionServiceTaskDelegate.ERR_ACTION_FAILED);

        Map<String, Object> failure = (Map<String, Object>) vars.get("_action_sms_node_result");
        assertThat(vars).containsEntry("_action_sms_node_success", false);
        assertThat(failure)
                .containsEntry("actionType", "SEND_SMS")
                .containsEntry("failureReason", "provider_unavailable")
                .containsEntry("error", "No real SMS sender available");
    }

    @Test
    void execute_extensionThrowsBusinessException_propagatedAsIs() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_ACTION, "iot:domain-error");
        when(extensionRegistry.getServiceTaskAction("iot:domain-error")).thenReturn(Optional.of(action));
        when(action.execute(any())).thenThrow(new BusinessException("iot.error.domain_specific"));

        ExecutionContext ctx = mockContext(props, new HashMap<>(), "node");
        assertThatThrownBy(() -> delegate.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("iot.error.domain_specific");
    }

    private ExecutionContext mockContext(Map<String, String> properties,
                                         Map<String, Object> request, String activityId) {
        return mockContext(properties, request, activityId, null);
    }

    private ExecutionContext mockContext(Map<String, String> properties,
                                         Map<String, Object> request,
                                         String activityId,
                                         String executionId) {
        ExecutionContext ctx = org.mockito.Mockito.mock(ExecutionContext.class);
        IdBasedElement element = org.mockito.Mockito.mock(IdBasedElement.class);
        when(ctx.getRequest()).thenReturn(request);
        when(ctx.getBaseElement()).thenReturn(element);
        lenient().when(element.getProperties()).thenReturn(properties);
        lenient().when(element.getId()).thenReturn(activityId);
        if (executionId != null) {
            ExecutionInstance executionInstance = org.mockito.Mockito.mock(ExecutionInstance.class);
            when(executionInstance.getInstanceId()).thenReturn(executionId);
            when(ctx.getExecutionInstance()).thenReturn(executionInstance);
        }
        ProcessDefinition processDefinition = org.mockito.Mockito.mock(ProcessDefinition.class);
        lenient().when(processDefinition.getId()).thenReturn("sms_process");
        lenient().when(ctx.getProcessDefinition()).thenReturn(processDefinition);
        return ctx;
    }
}
