package com.auraboot.framework.automation.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.AutomationLog.ActionResult;
import com.auraboot.framework.automation.entity.AutomationNodeExecution;
import com.auraboot.framework.automation.executor.ActionExecutionException;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.automation.mapper.AutomationNodeExecutionMapper;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    @AfterEach
    void clearMetaContext() {
        MetaContext.clear();
    }

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
        vars.put(AutomationActionServiceTaskDelegate.USER_ID_VAR, 11L);
        vars.put(AutomationActionServiceTaskDelegate.USER_PID_VAR, "user-11");
        vars.put(AutomationActionServiceTaskDelegate.USERNAME_VAR, "Automation Owner");
        vars.put(AutomationActionServiceTaskDelegate.MEMBER_ID_VAR, 22L);
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
    void failurePath_preservesStructuredActionPayloadWhenExecutorProvidesIt() {
        ActionExecutor executor = mock(ActionExecutor.class);
        AutomationNodeExecutionMapper mapper = mock(AutomationNodeExecutionMapper.class);
        when(mapper.insert(any(AutomationNodeExecution.class))).thenAnswer(inv -> {
            ((AutomationNodeExecution) inv.getArgument(0)).setId(303L);
            return 1;
        });
        when(executor.execute(any(), any()))
                .thenThrow(new ActionExecutionException("No real SMS sender available",
                        Map.of(
                                "channel", "sms",
                                "failureReason", "sms_delivery_failed",
                                "errorMessage", "No real SMS sender available"),
                        new IllegalStateException("No real SMS sender available")));

        AutomationActionServiceTaskDelegate delegate =
                new AutomationActionServiceTaskDelegate(executor, mapper);
        Map<String, Object> vars = processVars("a1", 42L, 7L);
        vars.put(AutomationActionServiceTaskDelegate.ACTION_RESULTS_VAR, new ArrayList<ActionResult>());

        assertThatThrownBy(() -> delegate.execute(contextWith("a1", vars)))
                .isInstanceOf(ActionExecutionException.class)
                .hasMessageContaining("No real SMS sender available");

        @SuppressWarnings("unchecked")
        List<ActionResult> actionResults =
                (List<ActionResult>) vars.get(AutomationActionServiceTaskDelegate.ACTION_RESULTS_VAR);
        assertThat(actionResults).hasSize(1);
        ActionResult result = actionResults.getFirst();
        assertThat(result.getStatus()).isEqualTo(StatusConstants.FAILED);
        assertThat(result.getErrorMessage()).contains("No real SMS sender available");
        assertThat(result.getResult()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.getResult();
        assertThat(payload)
                .containsEntry("channel", "sms")
                .containsEntry("failureReason", "sms_delivery_failed")
                .containsEntry("errorMessage", "No real SMS sender available");
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

    @Test
    void execute_establishesAutomationActorMetaContext_whenThreadHasNoContext() {
        MetaContext.clear();
        ActionExecutor executor = mock(ActionExecutor.class);
        when(executor.execute(any(), any())).thenAnswer(inv -> {
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(7L);
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(11L);
            assertThat(MetaContext.getCurrentUserPid()).isEqualTo("user-11");
            assertThat(MetaContext.getCurrentUsername()).isEqualTo("Automation Owner");
            assertThat(MetaContext.getCurrentMemberId()).isEqualTo(22L);
            return Map.of("ok", true);
        });
        AutomationActionServiceTaskDelegate delegate =
                new AutomationActionServiceTaskDelegate(executor);

        delegate.execute(contextWith("a1", processVars("a1", 42L, 7L)));

        assertThat(MetaContext.exists())
                .as("delegate must restore the caller thread after temporary automation context")
                .isFalse();
    }

    @Test
    void execute_upgradesTenantOnlyMetaContext_toAutomationActorContext() {
        MetaContext.setSystemTenantContext(7L);
        ActionExecutor executor = mock(ActionExecutor.class);
        when(executor.execute(any(), any())).thenAnswer(inv -> {
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(7L);
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(11L);
            assertThat(MetaContext.getCurrentMemberId()).isEqualTo(22L);
            return Map.of("ok", true);
        });
        AutomationActionServiceTaskDelegate delegate =
                new AutomationActionServiceTaskDelegate(executor);

        delegate.execute(contextWith("a1", processVars("a1", 42L, 7L)));

        assertThat(MetaContext.exists()).isTrue();
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(7L);
        assertThat(MetaContext.getCurrentUserId()).isNull();
        assertThat(MetaContext.getCurrentMemberId()).isNull();
    }

    @Test
    void execute_restoresPreviousMetaContext_afterAutomationActorContext() {
        MetaContext.setContext(99L, 88L, "previous-user", "Previous User");
        MetaContext.setMemberId(77L);
        MetaContext.setEnvironmentId(66L);
        MetaContext.setOtelTraceId("trace-previous");
        Map<String, Object> vars = processVars("a1", 42L, 7L);
        vars.put(AutomationActionServiceTaskDelegate.USER_ID_VAR, 11L);
        vars.put(AutomationActionServiceTaskDelegate.MEMBER_ID_VAR, 22L);
        ActionExecutor executor = mock(ActionExecutor.class);
        when(executor.execute(any(), any())).thenAnswer(inv -> {
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(7L);
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(11L);
            assertThat(MetaContext.getCurrentMemberId()).isEqualTo(22L);
            return Map.of("ok", true);
        });
        AutomationActionServiceTaskDelegate delegate =
                new AutomationActionServiceTaskDelegate(executor);

        delegate.execute(contextWith("a1", vars));

        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(99L);
        assertThat(MetaContext.getCurrentUserId()).isEqualTo(88L);
        assertThat(MetaContext.getCurrentUserPid()).isEqualTo("previous-user");
        assertThat(MetaContext.getCurrentUsername()).isEqualTo("Previous User");
        assertThat(MetaContext.getCurrentMemberId()).isEqualTo(77L);
        assertThat(MetaContext.getCurrentEnvironmentId()).isEqualTo(66L);
        assertThat(MetaContext.getOtelTraceId()).isEqualTo("trace-previous");
    }

    @Test
    void execute_clearsPreviousMemberId_whenAutomationActorHasNoMember() {
        MetaContext.setContext(99L, 88L, "previous-user", "Previous User");
        MetaContext.setMemberId(77L);
        Map<String, Object> vars = processVars("a1", 42L, 7L);
        vars.put(AutomationActionServiceTaskDelegate.USER_ID_VAR, 0L);
        vars.put(AutomationActionServiceTaskDelegate.USER_PID_VAR, "automation:AUTO-1");
        vars.put(AutomationActionServiceTaskDelegate.USERNAME_VAR, "automation");
        vars.remove(AutomationActionServiceTaskDelegate.MEMBER_ID_VAR);
        ActionExecutor executor = mock(ActionExecutor.class);
        when(executor.execute(any(), any())).thenAnswer(inv -> {
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(7L);
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(0L);
            assertThat(MetaContext.getCurrentUserPid()).isEqualTo("automation:AUTO-1");
            assertThat(MetaContext.getCurrentUsername()).isEqualTo("automation");
            assertThat(MetaContext.getCurrentMemberId()).isNull();
            return Map.of("ok", true);
        });
        AutomationActionServiceTaskDelegate delegate =
                new AutomationActionServiceTaskDelegate(executor);

        delegate.execute(contextWith("a1", vars));

        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(99L);
        assertThat(MetaContext.getCurrentUserId()).isEqualTo(88L);
        assertThat(MetaContext.getCurrentMemberId()).isEqualTo(77L);
    }
}
