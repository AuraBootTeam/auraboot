package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.ServiceTaskActionExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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

    @InjectMocks
    private PluginActionServiceTaskDelegate delegate;

    @Test
    void execute_happyPath_invokesActionAndWritesResultVars() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put(BpmServiceTaskConstants.ATTR_ACTION, "iot:recalibrate-sensor");
        props.put(BpmServiceTaskConstants.ATTR_RESULT_VAR, "calibResult");
        props.put("deviceId", "dev-1");

        Map<String, Object> vars = new HashMap<>();
        vars.put("deviceId", "dev-1");

        Map<String, Object> result = Map.of("calibrated", true);
        when(extensionRegistry.getServiceTaskAction("iot:recalibrate-sensor")).thenReturn(Optional.of(action));
        when(action.execute(any())).thenReturn(result);

        ExecutionContext ctx = mockContext(props, vars, "recalibrate_node");
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
        ExecutionContext ctx = org.mockito.Mockito.mock(ExecutionContext.class);
        IdBasedElement element = org.mockito.Mockito.mock(IdBasedElement.class);
        when(ctx.getRequest()).thenReturn(request);
        when(ctx.getBaseElement()).thenReturn(element);
        lenient().when(element.getProperties()).thenReturn(properties);
        lenient().when(element.getId()).thenReturn(activityId);
        return ctx;
    }
}
