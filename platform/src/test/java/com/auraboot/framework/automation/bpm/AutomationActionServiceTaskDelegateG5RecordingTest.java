package com.auraboot.framework.automation.bpm;

import com.auraboot.framework.automation.entity.AutomationNodeExecution;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.automation.mapper.AutomationNodeExecutionMapper;
import com.auraboot.framework.common.constant.StatusConstants;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G5 — unit coverage for {@link AutomationActionServiceTaskDelegate}'s node-status
 * recording branch. Focus: insert-on-entry / update-on-exit / failure path
 * propagates the exception AFTER stamping the failed row + recording is no-op when
 * the log id / tenant id / mapper are absent (back-compat with existing call sites).
 */
class AutomationActionServiceTaskDelegateG5RecordingTest {

    private ExecutionContext contextWith(String nodeId, Map<String, Object> vars) {
        IdBasedElement element = mock(IdBasedElement.class);
        when(element.getId()).thenReturn(nodeId);
        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getBaseElement()).thenReturn(element);
        when(ctx.getRequest()).thenReturn(vars);
        return ctx;
    }

    private Map<String, Object> processVars(String nodeId, Long logId, Long tenantId) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(AutomationActionServiceTaskDelegate.ACTIONS_VAR, Map.of(
                nodeId, Map.of("type", "send_notification", "config", Map.of())));
        if (logId != null) {
            vars.put(AutomationActionServiceTaskDelegate.LOG_ID_VAR, logId);
        }
        if (tenantId != null) {
            vars.put(AutomationActionServiceTaskDelegate.TENANT_ID_VAR, tenantId);
        }
        vars.put(AutomationActionServiceTaskDelegate.AUTOMATION_ID_VAR, "AUTO-1");
        return vars;
    }

    @Test
    void successPath_insertsRunningRow_thenUpdatesToCompleted() {
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationNodeExecutionMapper mapper = mock(AutomationNodeExecutionMapper.class);
        // Simulate the auto-id being assigned on insert so we have something to update.
        when(mapper.insert(any(AutomationNodeExecution.class))).thenAnswer(inv -> {
            ((AutomationNodeExecution) inv.getArgument(0)).setId(101L);
            return 1;
        });

        AutomationActionServiceTaskDelegate delegate =
                new AutomationActionServiceTaskDelegate(executor, mapper);

        delegate.execute(contextWith("a1", processVars("a1", 42L, 7L)));

        ArgumentCaptor<AutomationNodeExecution> insertCaptor =
                ArgumentCaptor.forClass(AutomationNodeExecution.class);
        verify(mapper).insert(insertCaptor.capture());
        AutomationNodeExecution inserted = insertCaptor.getValue();
        assertThat(inserted.getStatus()).isEqualTo(StatusConstants.RUNNING);
        assertThat(inserted.getNodeId()).isEqualTo("a1");
        assertThat(inserted.getAutomationLogId()).isEqualTo(42L);
        assertThat(inserted.getTenantId()).isEqualTo(7L);
        assertThat(inserted.getAutomationId()).isEqualTo("AUTO-1");
        assertThat(inserted.getStartedAt()).isNotNull();

        ArgumentCaptor<AutomationNodeExecution> updateCaptor =
                ArgumentCaptor.forClass(AutomationNodeExecution.class);
        verify(mapper).updateById(updateCaptor.capture());
        AutomationNodeExecution update = updateCaptor.getValue();
        assertThat(update.getId()).isEqualTo(101L);
        assertThat(update.getStatus()).isEqualTo(StatusConstants.COMPLETED);
        assertThat(update.getCompletedAt()).isNotNull();
        assertThat(update.getErrorMessage()).isNull();
    }

    @Test
    void failurePath_updatesToFailedWithErrorMessage_andPropagatesException() {
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationNodeExecutionMapper mapper = mock(AutomationNodeExecutionMapper.class);
        when(mapper.insert(any(AutomationNodeExecution.class))).thenAnswer(inv -> {
            ((AutomationNodeExecution) inv.getArgument(0)).setId(202L);
            return 1;
        });
        when(executor.execute(any(), any()))
                .thenThrow(new RuntimeException("downstream blew up"));

        AutomationActionServiceTaskDelegate delegate =
                new AutomationActionServiceTaskDelegate(executor, mapper);

        assertThatThrownBy(() -> delegate.execute(contextWith("a1", processVars("a1", 42L, 7L))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("downstream blew up");

        ArgumentCaptor<AutomationNodeExecution> updateCaptor =
                ArgumentCaptor.forClass(AutomationNodeExecution.class);
        verify(mapper).updateById(updateCaptor.capture());
        AutomationNodeExecution update = updateCaptor.getValue();
        assertThat(update.getStatus()).isEqualTo(StatusConstants.FAILED);
        assertThat(update.getErrorMessage()).contains("downstream blew up");
        assertThat(update.getCompletedAt()).isNotNull();
    }

    @Test
    void recordingDisabled_whenLogIdMissing_skipsMapperEntirely() {
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationNodeExecutionMapper mapper = mock(AutomationNodeExecutionMapper.class);

        AutomationActionServiceTaskDelegate delegate =
                new AutomationActionServiceTaskDelegate(executor, mapper);

        // tenant id present, log id absent → recording disabled, action still runs.
        delegate.execute(contextWith("a1", processVars("a1", null, 7L)));

        verify(executor).execute(any(), any());
        verify(mapper, never()).insert(any(AutomationNodeExecution.class));
        verify(mapper, never()).updateById(any(AutomationNodeExecution.class));
    }

    @Test
    void recordingDisabled_whenTenantIdMissing_skipsMapperEntirely() {
        // Row-level isolation (red line §13): we refuse to write a row without a tenant id.
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationNodeExecutionMapper mapper = mock(AutomationNodeExecutionMapper.class);

        AutomationActionServiceTaskDelegate delegate =
                new AutomationActionServiceTaskDelegate(executor, mapper);

        delegate.execute(contextWith("a1", processVars("a1", 42L, null)));

        verify(executor).execute(any(), any());
        verify(mapper, never()).insert(any(AutomationNodeExecution.class));
        verify(mapper, never()).updateById(any(AutomationNodeExecution.class));
    }

    @Test
    void backCompatCtor_withoutMapper_recordingNoOps_butExecutorStillRuns() {
        // Existing call sites that use the 1-arg ctor must keep working: recording
        // is silently disabled (mapper is null) but executor delegation is intact.
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationActionServiceTaskDelegate delegate =
                new AutomationActionServiceTaskDelegate(executor);

        delegate.execute(contextWith("a1", processVars("a1", 42L, 7L)));

        verify(executor).execute(any(), any());
    }
}
