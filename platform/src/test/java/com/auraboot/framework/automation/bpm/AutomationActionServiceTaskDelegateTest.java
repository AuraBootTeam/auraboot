package com.auraboot.framework.automation.bpm;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AutomationActionServiceTaskDelegate}: it resolves its node's
 * action spec from the {@code _automation_actions} process variable and delegates to
 * the existing {@link ActionExecutor}.
 */
class AutomationActionServiceTaskDelegateTest {

    private ExecutionContext contextWith(String nodeId, Map<String, Object> vars) {
        IdBasedElement element = mock(IdBasedElement.class);
        when(element.getId()).thenReturn(nodeId);
        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getBaseElement()).thenReturn(element);
        when(ctx.getRequest()).thenReturn(vars);
        return ctx;
    }

    @Test
    void execute_resolvesActionSpecByNodeId_andDelegatesToActionExecutor() {
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationActionServiceTaskDelegate delegate = new AutomationActionServiceTaskDelegate(executor);

        Map<String, Object> vars = new HashMap<>();
        vars.put(AutomationActionServiceTaskDelegate.ACTIONS_VAR, Map.of(
                "a1", Map.of("type", "send_notification", "config", Map.of("title", "hi"))));
        vars.put("recordId", "rec-1");

        delegate.execute(contextWith("a1", vars));

        ArgumentCaptor<AutomationAction> captor = ArgumentCaptor.forClass(AutomationAction.class);
        verify(executor).execute(captor.capture(), any());
        assertThat(captor.getValue().getType()).isEqualTo("send_notification");
        assertThat(captor.getValue().getConfig()).containsEntry("title", "hi");
    }

    @Test
    void execute_throwsWhenActionsVariableMissing() {
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationActionServiceTaskDelegate delegate = new AutomationActionServiceTaskDelegate(executor);

        assertThatThrownBy(() -> delegate.execute(contextWith("a1", new HashMap<>())))
                .isInstanceOf(IllegalStateException.class);
    }
}
