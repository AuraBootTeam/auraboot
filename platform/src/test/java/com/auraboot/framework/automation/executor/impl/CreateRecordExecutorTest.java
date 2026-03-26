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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CreateRecordExecutor.
 */
@ExtendWith(MockitoExtension.class)
class CreateRecordExecutorTest {

    @Mock
    private DynamicDataService dynamicDataService;

    @InjectMocks
    private CreateRecordExecutor executor;

    // =========================================================
    // supports()
    // =========================================================

    @Test
    void supports_createRecord_returnsTrue() {
        assertThat(executor.supports("create_record")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(executor.supports("update_record")).isFalse();
        assertThat(executor.supports("condition")).isFalse();
    }

    // =========================================================
    // execute() — happy path
    // =========================================================

    @Test
    @SuppressWarnings("unchecked")
    void execute_basicFields_createsRecord() {
        Map<String, Object> createdRecord = Map.of("id", 100L, "name", "John", "status", "new");
        when(dynamicDataService.create(eq("crm_lead"), any())).thenReturn(createdRecord);

        AutomationAction action = buildAction(Map.of(
                "modelCode", "crm_lead",
                "fields", Map.of("name", "John", "status", "new")
        ));
        Map<String, Object> context = Map.of();

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, context);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("modelCode")).isEqualTo("crm_lead");
        assertThat(result.get("record")).isEqualTo(createdRecord);
        verify(dynamicDataService).create(eq("crm_lead"), argThat(fields ->
                fields.get("name").equals("John") && fields.get("status").equals("new")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_variableSubstitution_resolvesFromContext() {
        when(dynamicDataService.create(any(), any())).thenReturn(Map.of("id", 200L));

        Map<String, Object> context = new HashMap<>();
        context.put("recordId", "lead-456");
        context.put("userId", 789L);

        AutomationAction action = buildAction(Map.of(
                "modelCode", "crm_activity",
                "fields", Map.of(
                        "relatedLeadId", "${recordId}",
                        "assigneeId", "${userId}",
                        "title", "Follow up"
                )
        ));

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, context);

        assertThat(result.get("success")).isEqualTo(true);
        verify(dynamicDataService).create(eq("crm_activity"), argThat(fields ->
                "lead-456".equals(fields.get("relatedLeadId")) &&
                789L == (long) fields.get("assigneeId") &&
                "Follow up".equals(fields.get("title"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_nestedVariableSubstitution_resolvesViaDotNotation() {
        when(dynamicDataService.create(any(), any())).thenReturn(Map.of("id", 300L));

        Map<String, Object> record = Map.of("id", 456L, "email", "test@example.com");
        Map<String, Object> context = new HashMap<>();
        context.put("record", record);

        AutomationAction action = buildAction(Map.of(
                "modelCode", "task",
                "fields", Map.of("email", "${record.email}", "sourceId", "${record.id}")
        ));

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, context);

        assertThat(result.get("success")).isEqualTo(true);
        verify(dynamicDataService).create(eq("task"), argThat(fields ->
                "test@example.com".equals(fields.get("email")) &&
                Long.valueOf(456L).equals(fields.get("sourceId"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_undefinedVariable_resolvesToNull() {
        when(dynamicDataService.create(any(), any())).thenReturn(Map.of("id", 400L));

        AutomationAction action = buildAction(Map.of(
                "modelCode", "task",
                "fields", Map.of("assignee", "${nonExistentVar}")
        ));

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of());

        assertThat(result.get("success")).isEqualTo(true);
        verify(dynamicDataService).create(eq("task"), argThat(fields ->
                fields.get("assignee") == null));
    }

    // =========================================================
    // execute() — validation errors
    // =========================================================

    @Test
    void execute_nullConfig_throwsIllegalArgument() {
        AutomationAction action = AutomationAction.builder()
                .type("create_record")
                .config(null)
                .build();

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config");
    }

    @Test
    void execute_missingModelCode_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of(
                "fields", Map.of("name", "John")
        ));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelCode");
    }

    @Test
    void execute_blankModelCode_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of(
                "modelCode", "   ",
                "fields", Map.of("name", "John")
        ));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelCode");
    }

    @Test
    void execute_nullFields_throwsIllegalArgument() {
        Map<String, Object> config = new HashMap<>();
        config.put("modelCode", "crm_lead");
        config.put("fields", null);
        AutomationAction action = buildAction(config);

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields");
    }

    @Test
    void execute_emptyFields_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of(
                "modelCode", "crm_lead",
                "fields", Map.of()
        ));

        assertThatThrownBy(() -> executor.execute(action, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields");
    }

    // =========================================================
    // Helper
    // =========================================================

    private AutomationAction buildAction(Map<String, Object> config) {
        return AutomationAction.builder()
                .type("create_record")
                .config(config)
                .build();
    }
}
