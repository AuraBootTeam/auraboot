package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UpdateRecordExecutor.
 */
@ExtendWith(MockitoExtension.class)
class UpdateRecordExecutorTest {

    @Mock
    private DynamicDataService dynamicDataService;

    @InjectMocks
    private UpdateRecordExecutor executor;

    // =========================================================
    // supports()
    // =========================================================

    @Test
    void supports_updateRecord_returnsTrue() {
        assertThat(executor.supports("update_record")).isTrue();
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
    void execute_withExplicitRecordId_updatesCorrectRecord() {
        when(dynamicDataService.update(any(), any(), any())).thenReturn(Map.of());

        AutomationAction action = buildAction(Map.of(
                "modelCode", "crm_lead",
                "recordId", "lead-123",
                "fields", Map.of("status", "qualified")
        ));

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of());

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("recordId")).isEqualTo("lead-123");
        @SuppressWarnings("unchecked")
        Set<String> updatedFields = (Set<String>) result.get("updatedFields");
        assertThat(updatedFields).contains("status");
        verify(dynamicDataService).update(eq("crm_lead"), eq("lead-123"), argThat(f ->
                "qualified".equals(f.get("status"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_recordIdFromContext_usesContextRecordId() {
        when(dynamicDataService.update(any(), any(), any())).thenReturn(Map.of());

        AutomationAction action = buildAction(Map.of(
                "modelCode", "crm_lead",
                "fields", Map.of("priority", "high")
        ));
        Map<String, Object> context = Map.of("recordId", "ctx-record-789");

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, context);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("recordId")).isEqualTo("ctx-record-789");
        verify(dynamicDataService).update(eq("crm_lead"), eq("ctx-record-789"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_variableSubstitution_resolvesFieldValues() {
        when(dynamicDataService.update(any(), any(), any())).thenReturn(Map.of());

        Map<String, Object> context = new HashMap<>();
        context.put("recordId", "rec-111");
        context.put("newStatus", "closed");

        AutomationAction action = buildAction(Map.of(
                "modelCode", "deal",
                "fields", Map.of("status", "${newStatus}", "closedAt", "2026-01-01")
        ));

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, context);

        assertThat(result.get("success")).isEqualTo(true);
        verify(dynamicDataService).update(eq("deal"), eq("rec-111"), argThat(f ->
                "closed".equals(f.get("status")) && "2026-01-01".equals(f.get("closedAt"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_nestedVariableFromContext_resolvedViaDotNotation() {
        when(dynamicDataService.update(any(), any(), any())).thenReturn(Map.of());

        Map<String, Object> record = Map.of("owner", "user-42");
        Map<String, Object> context = new HashMap<>();
        context.put("recordId", "rec-200");
        context.put("record", record);

        AutomationAction action = buildAction(Map.of(
                "modelCode", "ticket",
                "fields", Map.of("assignee", "${record.owner}")
        ));

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, context);

        assertThat(result.get("success")).isEqualTo(true);
        verify(dynamicDataService).update(eq("ticket"), eq("rec-200"), argThat(f ->
                "user-42".equals(f.get("assignee"))));
    }

    // =========================================================
    // execute() — validation errors
    // =========================================================

    @Test
    void execute_nullConfig_throwsIllegalArgument() {
        AutomationAction action = AutomationAction.builder()
                .type("update_record")
                .config(null)
                .build();

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config");
    }

    @Test
    void execute_emptyFields_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of(
                "modelCode", "crm_lead",
                "recordId", "rec-001",
                "fields", Map.of()
        ));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields");
    }

    @Test
    void execute_nullFields_throwsIllegalArgument() {
        Map<String, Object> config = new HashMap<>();
        config.put("modelCode", "crm_lead");
        config.put("recordId", "rec-001");
        config.put("fields", null);
        AutomationAction action = buildAction(config);

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields");
    }

    // =========================================================
    // Helper
    // =========================================================

    private AutomationAction buildAction(Map<String, Object> config) {
        return AutomationAction.builder()
                .type("update_record")
                .config(config)
                .build();
    }
}
