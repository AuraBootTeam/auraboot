package com.auraboot.framework.automation.service;

import com.auraboot.framework.automation.service.AutomationFlowTriggerDeriver.DerivedTrigger;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.exception.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AutomationFlowTriggerDeriver}.
 *
 * <p>These tests run with zero DB / Spring context — just a plain ObjectMapper.
 * They are the regression proof that the P0 gap (designer automations never fire
 * because triggerType/modelCode were never persisted) is fixed.
 */
class AutomationFlowTriggerDeriverTest {

    private final AutomationFlowTriggerDeriver deriver =
            new AutomationFlowTriggerDeriver(new ObjectMapper());

    // ==================== Helper factories ====================

    private static Map<String, Object> flowConfig(List<Map<String, Object>> nodes) {
        return Map.of("nodes", nodes, "edges", List.of());
    }

    private static Map<String, Object> triggerNode(String type, Map<String, Object> config) {
        return Map.of(
                "id", "t1",
                "type", type,
                "data", Map.of("label", type, "config", config));
    }

    private static Map<String, Object> actionNode() {
        return Map.of(
                "id", "a1",
                "type", "action-send-notification",
                "data", Map.of("label", "Notify",
                        "config", Map.of("actionType", "send_notification")));
    }

    // ==================== P0 regression: record-create trigger ====================

    /**
     * Designer saves a trigger-record-create node.
     * Verifies triggerType=on_record_create and modelCode are extracted from data.config.
     * This is the primary regression proof for the P0 fix.
     */
    @Test
    void derive_recordCreateTrigger_extractsTriggerTypeAndModelCode() {
        Map<String, Object> cfg = Map.of(
                "triggerType", "on_record_create",
                "modelCode", "crm_lead");
        Map<String, Object> fc = flowConfig(List.of(
                triggerNode("trigger-record-create", cfg),
                actionNode()));

        DerivedTrigger result = deriver.derive(fc);

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.triggerType()).isEqualTo("on_record_create");
        assertThat(result.modelCode()).isEqualTo("crm_lead");
    }

    @Test
    void derive_recordCreateTrigger_preservesRuleCenterBinding() {
        Map<String, Object> ruleBinding = Map.of(
                "consumerType", "AUTOMATION",
                "consumerNodeId", "trigger",
                "bindingKind", "DECISION_REF",
                "enabled", true,
                "decisionBinding", Map.of(
                        "decisionCode", "lead_routing",
                        "versionPolicy", "LATEST_PUBLISHED",
                        "inputMappings", List.of(),
                        "outputMappings", List.of(),
                        "fallbackPolicy", Map.of("mode", "FAIL_CLOSED"),
                        "traceMode", "SAMPLED",
                        "enabled", true));
        Map<String, Object> cfg = Map.of(
                "triggerType", "on_record_create",
                "modelCode", "crm_lead",
                "ruleBinding", ruleBinding);
        Map<String, Object> fc = flowConfig(List.of(
                triggerNode("trigger-record-create", cfg),
                actionNode()));

        DerivedTrigger result = deriver.derive(fc);

        assertThat(result.triggerConfig().getRuleBinding()).isNotNull();
        assertThat(result.triggerConfig().getRuleBinding().consumerType()).isEqualTo("AUTOMATION");
        assertThat(result.triggerConfig().getRuleBinding().consumerNodeId()).isEqualTo("trigger");
        assertThat(result.triggerConfig().getRuleBinding().bindingKind()).isEqualTo(RuleBindingKind.DECISION_REF);
        assertThat(result.triggerConfig().getRuleBinding().decisionBinding().decisionCode())
                .isEqualTo("lead_routing");
    }

    // ==================== BPM event trigger ====================

    /**
     * Designer saves a trigger-bpm-event node.
     * Verifies triggerType=on_bpm_event, modelCode (= processKey), and
     * triggerConfig.eventTypes are all correctly derived.
     */
    @Test
    void derive_bpmEventTrigger_extractsAllFields() {
        Map<String, Object> cfg = Map.of(
                "triggerType", "on_bpm_event",
                "modelCode", "leave_approval",
                "eventTypes", List.of("process_started", "task_completed"));
        Map<String, Object> fc = flowConfig(List.of(
                triggerNode("trigger-bpm-event", cfg)));

        DerivedTrigger result = deriver.derive(fc);

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.triggerType()).isEqualTo("on_bpm_event");
        assertThat(result.modelCode()).isEqualTo("leave_approval");
        assertThat(result.triggerConfig()).isNotNull();
        assertThat(result.triggerConfig().getEventTypes())
                .containsExactly("process_started", "task_completed");
    }

    // ==================== Inactivity trigger ====================

    @Test
    void derive_inactivityTrigger_extractsModelThresholdFieldAndStates() {
        Map<String, Object> cfg = Map.of(
                "triggerType", "on_inactivity",
                "modelCode", "crm_lead",
                "inactivityHours", 24,
                "inactivityField", "last_contacted_at",
                "stateField", "lead_status",
                "inactivityStates", List.of("open", "nurturing"));
        Map<String, Object> fc = flowConfig(List.of(
                triggerNode("trigger-inactivity", cfg)));

        DerivedTrigger result = deriver.derive(fc);

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.triggerType()).isEqualTo("on_inactivity");
        assertThat(result.modelCode()).isEqualTo("crm_lead");
        assertThat(result.triggerConfig()).isNotNull();
        assertThat(result.triggerConfig().getInactivityHours()).isEqualTo(24);
        assertThat(result.triggerConfig().getInactivityField()).isEqualTo("last_contacted_at");
        assertThat(result.triggerConfig().getStateField()).isEqualTo("lead_status");
        assertThat(result.triggerConfig().getInactivityStates())
                .containsExactly("open", "nurturing");
    }

    // ==================== data.config path verification ====================

    /**
     * A node that stores config at the WRONG location (node.config rather than
     * node.data.config) must NOT be picked up. This mirrors AutomationFlowCompiler's
     * canonical read path of node.data.config, and proves a node in the wrong shape
     * causes a validation error rather than silent null values.
     */
    @Test
    void derive_configAtWrongLocation_throwsValidationException() {
        // Config is placed directly on the node, not under data.config
        Map<String, Object> wrongShapeNode = Map.of(
                "id", "t1",
                "type", "trigger-record-create",
                "config", Map.of("triggerType", "on_record_create", "modelCode", "crm_lead"),
                // data exists but has no 'config' key
                "data", Map.of("label", "trigger"));

        Map<String, Object> fc = flowConfig(List.of(wrongShapeNode));

        assertThatThrownBy(() -> deriver.derive(fc))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("data.config");
    }

    // ==================== Validation: no trigger node ====================

    @Test
    void derive_noTriggerNode_throwsValidationException() {
        Map<String, Object> fc = flowConfig(List.of(actionNode()));

        assertThatThrownBy(() -> deriver.derive(fc))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no trigger node");
    }

    // ==================== Validation: two trigger nodes ====================

    @Test
    void derive_twoTriggerNodes_throwsValidationException() {
        Map<String, Object> cfg = Map.of("triggerType", "on_record_create", "modelCode", "x");
        Map<String, Object> fc = flowConfig(List.of(
                triggerNode("trigger-record-create", cfg),
                Map.of("id", "t2", "type", "trigger-record-update",
                        "data", Map.of("label", "t2", "config", cfg))));

        assertThatThrownBy(() -> deriver.derive(fc))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exactly one trigger node");
    }

    // ==================== Empty / no-nodes flowConfig ====================

    @Test
    void derive_emptyFlowConfig_returnsEmpty() {
        assertThat(deriver.derive(Map.of())).satisfies(r -> assertThat(r.isEmpty()).isTrue());
    }

    @Test
    void derive_nullFlowConfig_returnsEmpty() {
        assertThat(deriver.derive(null)).satisfies(r -> assertThat(r.isEmpty()).isTrue());
    }

    @Test
    void derive_flowConfigWithEmptyNodesList_returnsEmpty() {
        Map<String, Object> fc = Map.of("nodes", List.of(), "edges", List.of());
        assertThat(deriver.derive(fc)).satisfies(r -> assertThat(r.isEmpty()).isTrue());
    }
}
