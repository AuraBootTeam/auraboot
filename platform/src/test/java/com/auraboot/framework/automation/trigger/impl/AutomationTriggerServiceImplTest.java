package com.auraboot.framework.automation.trigger.impl;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.AutomationLog;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.automation.mapper.AutomationLogMapper;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AutomationTriggerServiceImpl.
 * Tests condition evaluation, watch-field filtering, and field/state change filtering.
 * @Async annotations have no effect without Spring context — methods run synchronously.
 */
@ExtendWith(MockitoExtension.class)
class AutomationTriggerServiceImplTest {

    @Mock
    private AutomationMapper automationMapper;

    @Mock
    private AutomationLogMapper automationLogMapper;

    @Mock
    private ActionExecutor actionExecutor;

    private AutomationTriggerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AutomationTriggerServiceImpl(automationMapper, automationLogMapper, actionExecutor);
    }

    // =========================================================
    // evaluateCondition — blank / null
    // =========================================================

    @Test
    void evaluateCondition_nullCondition_returnsTrue() {
        assertThat(service.evaluateCondition(null, Map.of())).isTrue();
    }

    @Test
    void evaluateCondition_emptyCondition_returnsTrue() {
        assertThat(service.evaluateCondition("", Map.of())).isTrue();
    }

    @Test
    void evaluateCondition_blankCondition_returnsTrue() {
        assertThat(service.evaluateCondition("   ", Map.of())).isTrue();
    }

    // =========================================================
    // evaluateCondition — simple literals
    // =========================================================

    @Test
    void evaluateCondition_literalTrue_returnsTrue() {
        assertThat(service.evaluateCondition("true", Map.of())).isTrue();
    }

    @Test
    void evaluateCondition_literalFalse_returnsFalse() {
        assertThat(service.evaluateCondition("false", Map.of())).isFalse();
    }

    // =========================================================
    // evaluateCondition — context variable binding
    // =========================================================

    @Test
    void evaluateCondition_variableEquality_evaluatesCorrectly() {
        Map<String, Object> context = Map.of("status", "active");
        assertThat(service.evaluateCondition("#status == 'active'", context)).isTrue();
    }

    @Test
    void evaluateCondition_variableEquality_wrongValue_returnsFalse() {
        Map<String, Object> context = Map.of("status", "inactive");
        assertThat(service.evaluateCondition("#status == 'active'", context)).isFalse();
    }

    @Test
    void evaluateCondition_numericComparison_returnsTrue() {
        Map<String, Object> context = Map.of("amount", 500);
        assertThat(service.evaluateCondition("#amount > 100", context)).isTrue();
    }

    @Test
    void evaluateCondition_numericComparison_returnsFalse() {
        Map<String, Object> context = Map.of("amount", 50);
        assertThat(service.evaluateCondition("#amount > 100", context)).isFalse();
    }

    // =========================================================
    // evaluateCondition — security: dangerous expressions
    // =========================================================

    @Test
    void evaluateCondition_dangerousExpression_T_returnsFalse() {
        assertThat(service.evaluateCondition("T(java.lang.Runtime).getRuntime()", Map.of())).isFalse();
    }

    @Test
    void evaluateCondition_dangerousExpression_new_returnsFalse() {
        assertThat(service.evaluateCondition("new java.lang.ProcessBuilder('ls').start()", Map.of())).isFalse();
    }

    @Test
    void evaluateCondition_dangerousExpression_getClass_returnsFalse() {
        assertThat(service.evaluateCondition("#root.getClass()", Map.of())).isFalse();
    }

    @Test
    void evaluateCondition_dangerousExpression_systemExit_returnsFalse() {
        assertThat(service.evaluateCondition("T(System).exit(0)", Map.of())).isFalse();
    }

    // =========================================================
    // evaluateCondition — security: length limit
    // =========================================================

    @Test
    void evaluateCondition_exceedsMaxLength_returnsFalse() {
        String longExpression = "true".repeat(200); // > 500 chars
        assertThat(service.evaluateCondition(longExpression, Map.of())).isFalse();
    }

    // =========================================================
    // evaluateCondition — invalid SpEL
    // =========================================================

    @Test
    void evaluateCondition_invalidSpel_returnsFalse() {
        assertThat(service.evaluateCondition("{{{{ not valid spel }", Map.of())).isFalse();
    }

    // =========================================================
    // onRecordUpdate — watch fields filtering
    // =========================================================

    @Test
    void onRecordUpdate_noWatchFields_alwaysTriggers() {
        TriggerConfig config = new TriggerConfig();
        config.setWatchFields(null);
        Automation automation = buildAutomation("auto-001", "model-A", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-A", "on_record_update"))
                .thenReturn(List.of(automation));

        Map<String, Object> before = Map.of("status", "draft");
        Map<String, Object> after = Map.of("status", "draft"); // same value — no change

        service.onRecordUpdate("model-A", "rec-001", before, after);

        // No watch fields → always triggers regardless of data change
        verify(automationLogMapper).insertLog(any());
    }

    @Test
    void onRecordUpdate_watchedFieldChanged_triggers() {
        TriggerConfig config = new TriggerConfig();
        config.setWatchFields(List.of("status"));
        Automation automation = buildAutomation("auto-002", "model-B", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-B", "on_record_update"))
                .thenReturn(List.of(automation));

        Map<String, Object> before = Map.of("status", "draft");
        Map<String, Object> after = Map.of("status", "active"); // changed

        service.onRecordUpdate("model-B", "rec-002", before, after);

        verify(automationLogMapper).insertLog(any());
    }

    @Test
    void onRecordUpdate_watchedFieldNotChanged_doesNotTrigger() {
        TriggerConfig config = new TriggerConfig();
        config.setWatchFields(List.of("status"));
        Automation automation = buildAutomation("auto-003", "model-C", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-C", "on_record_update"))
                .thenReturn(List.of(automation));

        Map<String, Object> before = Map.of("status", "draft");
        Map<String, Object> after = Map.of("status", "draft"); // unchanged

        service.onRecordUpdate("model-C", "rec-003", before, after);

        // Should NOT have created a log (no trigger)
        verify(automationLogMapper, never()).insertLog(any());
    }

    // =========================================================
    // onFieldChange — from/to value constraints
    // =========================================================

    @Test
    void onFieldChange_matchesFieldCodeAndFromTo_triggers() {
        TriggerConfig config = new TriggerConfig();
        config.setFieldCode("priority");
        config.setFromValue("low");
        config.setToValue("high");
        Automation automation = buildAutomation("auto-004", "model-D", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-D", "on_field_change"))
                .thenReturn(List.of(automation));

        service.onFieldChange("model-D", "rec-004", "priority", "low", "high");

        verify(automationLogMapper).insertLog(any());
    }

    @Test
    void onFieldChange_wrongFieldCode_doesNotTrigger() {
        TriggerConfig config = new TriggerConfig();
        config.setFieldCode("status");
        Automation automation = buildAutomation("auto-005", "model-E", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-E", "on_field_change"))
                .thenReturn(List.of(automation));

        service.onFieldChange("model-E", "rec-005", "priority", "low", "high"); // wrong field

        verify(automationLogMapper, never()).insertLog(any());
    }

    @Test
    void onFieldChange_fromValueMismatch_doesNotTrigger() {
        TriggerConfig config = new TriggerConfig();
        config.setFieldCode("priority");
        config.setFromValue("medium"); // expects MEDIUM, but actual was LOW
        Automation automation = buildAutomation("auto-006", "model-F", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-F", "on_field_change"))
                .thenReturn(List.of(automation));

        service.onFieldChange("model-F", "rec-006", "priority", "low", "high");

        verify(automationLogMapper, never()).insertLog(any());
    }

    @Test
    void onFieldChange_toValueMismatch_doesNotTrigger() {
        TriggerConfig config = new TriggerConfig();
        config.setFieldCode("priority");
        config.setToValue("critical"); // expects CRITICAL, but actual was HIGH
        Automation automation = buildAutomation("auto-007", "model-G", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-G", "on_field_change"))
                .thenReturn(List.of(automation));

        service.onFieldChange("model-G", "rec-007", "priority", "low", "high");

        verify(automationLogMapper, never()).insertLog(any());
    }

    // =========================================================
    // onStateChange — from/to state constraints
    // =========================================================

    @Test
    void onStateChange_matchesFromAndToStates_triggers() {
        TriggerConfig config = new TriggerConfig();
        config.setFromStates(List.of("draft", "pending"));
        config.setToStates(List.of("active"));
        Automation automation = buildAutomation("auto-008", "model-H", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-H", "on_state_change"))
                .thenReturn(List.of(automation));

        service.onStateChange("model-H", "rec-008", "draft", "active");

        verify(automationLogMapper).insertLog(any());
    }

    @Test
    void onStateChange_fromStateNotInList_doesNotTrigger() {
        TriggerConfig config = new TriggerConfig();
        config.setFromStates(List.of("pending"));
        config.setToStates(List.of("active"));
        Automation automation = buildAutomation("auto-009", "model-I", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-I", "on_state_change"))
                .thenReturn(List.of(automation));

        service.onStateChange("model-I", "rec-009", "draft", "active"); // DRAFT not in fromStates

        verify(automationLogMapper, never()).insertLog(any());
    }

    @Test
    void onStateChange_toStateNotInList_doesNotTrigger() {
        TriggerConfig config = new TriggerConfig();
        config.setFromStates(List.of("draft"));
        config.setToStates(List.of("active"));
        Automation automation = buildAutomation("auto-010", "model-J", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-J", "on_state_change"))
                .thenReturn(List.of(automation));

        service.onStateChange("model-J", "rec-010", "draft", "suspended"); // SUSPENDED not in toStates

        verify(automationLogMapper, never()).insertLog(any());
    }

    // =========================================================
    // executeAutomation — actions in sequence
    // =========================================================

    @Test
    void executeAutomation_actionsExecutedInSequence() {
        AutomationAction action1 = AutomationAction.builder().type("send_notification").sequence(1).build();
        AutomationAction action2 = AutomationAction.builder().type("create_record").sequence(2).build();
        // Use ArrayList (mutable) — List.of() is immutable and actions.sort() would throw
        Automation automation = buildAutomation("auto-011", "model-K", null, null,
                new java.util.ArrayList<>(List.of(action2, action1))); // deliberately out of order

        when(actionExecutor.execute(any(), any())).thenReturn(Map.of("ok", true));

        AutomationLog log = service.executeAutomation(automation, "rec-011", Map.of());

        assertThat(log.getStatus()).isEqualTo("success");
        assertThat(log.getActionResults()).hasSize(2);
    }

    @Test
    void executeAutomation_actionFails_stopsByDefault() {
        AutomationAction action1 = AutomationAction.builder().type("send_notification").sequence(1)
                .continueOnError(false).build();
        AutomationAction action2 = AutomationAction.builder().type("create_record").sequence(2).build();
        Automation automation = buildAutomation("auto-012", "model-L", null, null,
                new java.util.ArrayList<>(List.of(action1, action2)));

        when(actionExecutor.execute(eq(action1), any()))
                .thenThrow(new RuntimeException("notification failed"));

        AutomationLog log = service.executeAutomation(automation, "rec-012", Map.of());

        assertThat(log.getStatus()).isEqualTo("failed");
        assertThat(log.getActionResults()).hasSize(1); // action2 never ran
    }

    @Test
    void executeAutomation_continueOnError_executesRemainingActions() {
        AutomationAction action1 = AutomationAction.builder().type("send_notification").sequence(1)
                .continueOnError(true).build();
        AutomationAction action2 = AutomationAction.builder().type("create_record").sequence(2).build();
        Automation automation = buildAutomation("auto-013", "model-M", null, null,
                new java.util.ArrayList<>(List.of(action1, action2)));

        when(actionExecutor.execute(eq(action1), any()))
                .thenThrow(new RuntimeException("non-critical failure"));
        when(actionExecutor.execute(eq(action2), any())).thenReturn(Map.of("ok", true));

        AutomationLog log = service.executeAutomation(automation, "rec-013", Map.of());

        assertThat(log.getActionResults()).hasSize(2);
        assertThat(log.getActionResults().get(0).getStatus()).isEqualTo("failed");
        assertThat(log.getActionResults().get(1).getStatus()).isEqualTo("success");
    }

    // =========================================================
    // Helper
    // =========================================================

    private Automation buildAutomation(String pid, String modelCode, String condition,
                                        TriggerConfig triggerConfig, List<AutomationAction> actions) {
        Automation automation = new Automation();
        automation.setPid(pid);
        automation.setModelCode(modelCode);
        automation.setTriggerCondition(condition);
        automation.setTriggerConfig(triggerConfig);
        // Use mutable list — executeAutomation calls actions.sort() which requires mutability
        automation.setActions(actions != null ? new java.util.ArrayList<>(actions) : null);
        automation.setTenantId(1L);
        return automation;
    }
}
