package com.auraboot.framework.automation.workflow;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.automation.workflow.AutomationActionAccessorImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AutomationActionAccessorImpl}: it resolves its node's
 * action spec from the {@code _automation_actions} process variable and delegates to
 * the existing {@link ActionExecutor}.
 */
class AutomationActionAccessorImplTest {

    @Test
    void execute_resolvesActionSpecByNodeId_andDelegatesToActionExecutor() {
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationActionAccessorImpl delegate = new AutomationActionAccessorImpl(executor);

        Map<String, Object> vars = new HashMap<>();
        vars.put(AutomationActionAccessorImpl.ACTIONS_VAR, Map.of(
                "a1", Map.of("type", "send_notification", "config", Map.of("title", "hi"))));
        vars.put("recordPid", "rec-1");

        delegate.execute("a1", null, vars);

        ArgumentCaptor<AutomationAction> captor = ArgumentCaptor.forClass(AutomationAction.class);
        verify(executor).execute(captor.capture(), any());
        assertThat(captor.getValue().getType()).isEqualTo("send_notification");
        assertThat(captor.getValue().getConfig()).containsEntry("title", "hi");
    }

    @Test
    void execute_throwsWhenActionsVariableMissing() {
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationActionAccessorImpl delegate = new AutomationActionAccessorImpl(executor);

        assertThatThrownBy(() -> delegate.execute("a1", null, new HashMap<>()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- P0-5: control-loop edge cases (delegate-internal for-each) ----

    @Test
    void execute_loopWithEmptyCollection_doesNotInvokeExecutor() {
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationActionAccessorImpl delegate = new AutomationActionAccessorImpl(executor);

        Map<String, Object> vars = new HashMap<>();
        vars.put(AutomationActionAccessorImpl.ACTIONS_VAR, Map.of(
                "body", Map.of(
                        "type", "send_notification",
                        "config", Map.of(),
                        "loop", Map.of("collection", "items", "itemVariable", "item"))));
        vars.put("items", List.of());

        delegate.execute("body", null, vars);

        verify(executor, never()).execute(any(), any());
    }

    @Test
    void execute_loopWithMissingCollectionVariable_doesNotInvokeExecutor() {
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationActionAccessorImpl delegate = new AutomationActionAccessorImpl(executor);

        Map<String, Object> vars = new HashMap<>();
        vars.put(AutomationActionAccessorImpl.ACTIONS_VAR, Map.of(
                "body", Map.of(
                        "type", "send_notification",
                        "config", Map.of(),
                        "loop", Map.of("collection", "items", "itemVariable", "item"))));
        // "items" variable intentionally absent (null) — must not throw, must not fire.

        delegate.execute("body", null, vars);

        verify(executor, never()).execute(any(), any());
    }

    @Test
    void execute_loopWithExceptionMidIteration_propagatesAndStopsRemainingIterations() {
        ActionExecutor executor = mock(ActionExecutor.class);
        // First call OK, second call throws — third must NOT happen (fail-fast contract).
        when(executor.execute(any(), any()))
                .thenReturn(Map.of("ok", true))
                .thenThrow(new RuntimeException("action failed on element b"));
        AutomationActionAccessorImpl delegate = new AutomationActionAccessorImpl(executor);

        Map<String, Object> vars = new HashMap<>();
        vars.put(AutomationActionAccessorImpl.ACTIONS_VAR, Map.of(
                "body", Map.of(
                        "type", "send_notification",
                        "config", Map.of(),
                        "loop", Map.of("collection", "items", "itemVariable", "item"))));
        vars.put("items", List.of("a", "b", "c"));

        assertThatThrownBy(() -> delegate.execute("body", null, vars))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("action failed on element b");

        // Iterations 1 and 2 executed (the second one raised); iteration 3 must NOT fire.
        verify(executor, times(2)).execute(any(), any());
    }

    @Test
    void execute_loopBindsItemVariablePerIteration() {
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationActionAccessorImpl delegate = new AutomationActionAccessorImpl(executor);

        Map<String, Object> vars = new HashMap<>();
        vars.put(AutomationActionAccessorImpl.ACTIONS_VAR, Map.of(
                "body", Map.of(
                        "type", "send_notification",
                        "config", Map.of(),
                        "loop", Map.of("collection", "items", "itemVariable", "row"))));
        vars.put("items", List.of("a", "b"));

        delegate.execute("body", null, vars);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(executor, times(2)).execute(any(), captor.capture());
        assertThat(captor.getAllValues().get(0)).containsEntry("row", "a");
        assertThat(captor.getAllValues().get(1)).containsEntry("row", "b");
    }

    @Test
    void execute_loopReachesIterationContextDoesNotLeakBetweenIterations() {
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationActionAccessorImpl delegate = new AutomationActionAccessorImpl(executor);

        Map<String, Object> vars = new HashMap<>();
        vars.put(AutomationActionAccessorImpl.ACTIONS_VAR, Map.of(
                "body", Map.of(
                        "type", "send_notification",
                        "config", Map.of(),
                        "loop", Map.of("collection", "items", "itemVariable", "item"))));
        vars.put("items", List.of("x", "y"));

        delegate.execute("body", null, vars);

        // The outer "vars" map must not have been mutated by the delegate — each iteration
        // works on a per-iteration copy. (Guards against subtle context bleed.)
        assertThat(vars).doesNotContainKey("item");
    }
}
