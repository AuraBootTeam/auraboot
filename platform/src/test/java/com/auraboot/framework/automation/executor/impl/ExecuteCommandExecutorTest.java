package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
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
 * Unit tests for ExecuteCommandExecutor.
 */
@ExtendWith(MockitoExtension.class)
class ExecuteCommandExecutorTest {

    @Mock
    private CommandExecutor commandExecutor;

    @InjectMocks
    private ExecuteCommandExecutor executor;

    // =========================================================
    // supports()
    // =========================================================

    @Test
    void supports_executeCommand_returnsTrue() {
        assertThat(executor.supports("execute_command")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(executor.supports("create_record")).isFalse();
        assertThat(executor.supports("condition")).isFalse();
    }

    // =========================================================
    // execute() — happy path
    // =========================================================

    @Test
    @SuppressWarnings("unchecked")
    void execute_basicCommand_executesAndReturnsResult() {
        CommandExecuteResult cmdResult = mock(CommandExecuteResult.class);
        when(cmdResult.getData()).thenReturn(Map.of("status", "done"));
        when(commandExecutor.execute(eq("approve_lead"), any())).thenReturn(cmdResult);

        Map<String, Object> params = new HashMap<>(Map.of("reason", "auto-approved"));
        AutomationAction action = buildAction(new HashMap<>(Map.of("commandCode", "approve_lead", "params", params)));
        Map<String, Object> context = Map.of("recordId", "lead-001");

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, context);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("commandCode")).isEqualTo("approve_lead");
        @SuppressWarnings("unchecked")
        Map<String, Object> innerResult = (Map<String, Object>) result.get("result");
        assertThat(innerResult).containsEntry("status", "done");
    }

    @Test
    void execute_recordIdFromContext_addedAsPidIfNotInParams() {
        CommandExecuteResult cmdResult = mock(CommandExecuteResult.class);
        when(cmdResult.getData()).thenReturn(Map.of());
        when(commandExecutor.execute(any(), any())).thenReturn(cmdResult);

        AutomationAction action = buildAction(Map.of("commandCode", "close_ticket"));
        Map<String, Object> context = Map.of("recordId", "ticket-999");

        executor.execute(action, context);

        ArgumentCaptor<CommandExecuteRequest> captor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor).execute(eq("close_ticket"), captor.capture());
        CommandExecuteRequest req = captor.getValue();
        assertThat(req.getPayload()).containsEntry("pid", "ticket-999");
        assertThat(req.getTargetRecordId()).isEqualTo("ticket-999");
    }

    @Test
    void execute_variableSubstitution_resolvesParamsFromContext() {
        CommandExecuteResult cmdResult = mock(CommandExecuteResult.class);
        when(cmdResult.getData()).thenReturn(Map.of());
        when(commandExecutor.execute(any(), any())).thenReturn(cmdResult);

        Map<String, Object> context = new HashMap<>();
        context.put("recordId", "rec-123");
        context.put("assigneeId", "user-456");

        Map<String, Object> params2 = new HashMap<>(Map.of("assignee", "${assigneeId}", "note", "auto-assigned"));
        Map<String, Object> config2 = new HashMap<>();
        config2.put("commandCode", "assign_task");
        config2.put("params", params2);
        AutomationAction action = buildAction(config2);

        executor.execute(action, context);

        ArgumentCaptor<CommandExecuteRequest> captor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor).execute(eq("assign_task"), captor.capture());
        assertThat(captor.getValue().getPayload()).containsEntry("assignee", "user-456");
        assertThat(captor.getValue().getPayload()).containsEntry("note", "auto-assigned");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_nullCommandResult_returnsEmptyMap() {
        CommandExecuteResult cmdResult = mock(CommandExecuteResult.class);
        when(cmdResult.getData()).thenReturn(null);
        when(commandExecutor.execute(any(), any())).thenReturn(cmdResult);

        AutomationAction action = buildAction(Map.of("commandCode", "notify"));

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> innerResult = (Map<String, Object>) result.get("result");
        assertThat(innerResult).isEmpty();
    }

    // =========================================================
    // execute() — validation
    // =========================================================

    @Test
    void execute_nullConfig_throwsIllegalArgument() {
        AutomationAction action = AutomationAction.builder()
                .type("execute_command")
                .config(null)
                .build();

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config");
    }

    @Test
    void execute_missingCommandCode_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of("params", Map.of("x", "y")));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commandCode");
    }

    // =========================================================
    // Helper
    // =========================================================

    private AutomationAction buildAction(Map<String, Object> config) {
        return AutomationAction.builder()
                .type("execute_command")
                .config(config)
                .build();
    }
}
