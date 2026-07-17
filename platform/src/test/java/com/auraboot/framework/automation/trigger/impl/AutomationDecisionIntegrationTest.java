package com.auraboot.framework.automation.trigger.impl;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.DecisionVersionPolicy;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleEvaluationContext;
import com.auraboot.framework.decision.rule.RuleEvaluationService;
import com.auraboot.framework.decision.rule.RuleEvaluationTrace;
import com.auraboot.framework.decision.rule.RuleMappingTarget;
import com.auraboot.framework.decision.rule.RuleValueSource;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M4 consumer integration — Automation trigger conditions can reference a DecisionRuntime decision
 * via {@code trigger_config.decisionRef}; the result is injected as a {@code #decision} variable.
 * Unit-tests the package-private {@code withDecision} injection (the additive seam); the full
 * trigger-fire end-to-end IT is a documented follow-on. No decisionRef -> payload unchanged
 * (so existing automations are unaffected).
 */
class AutomationDecisionIntegrationTest {

    private final DecisionEvaluationService decisionService = mock(DecisionEvaluationService.class);
    private final RuleEvaluationService ruleEvaluationService = mock(RuleEvaluationService.class);

    private AutomationTriggerServiceImpl service() {
        AutomationTriggerServiceImpl s = new AutomationTriggerServiceImpl(null, null, null);
        ReflectionTestUtils.setField(s, "decisionEvaluationService", decisionService);
        ReflectionTestUtils.setField(s, "ruleEvaluationService", ruleEvaluationService);
        return s;
    }

    private Automation automation(String decisionRef) {
        Automation a = new Automation();
        a.setId(1L);
        if (decisionRef != null) {
            TriggerConfig cfg = new TriggerConfig();
            cfg.setDecisionRef(decisionRef);
            a.setTriggerConfig(cfg);
        }
        return a;
    }

    private Automation automationWithRuleBinding() {
        Automation automation = new Automation();
        automation.setId(2L);
        automation.setPid("auto-rule-1");
        TriggerConfig cfg = new TriggerConfig();
        cfg.setRuleBinding(new RuleConsumerBinding(
                "AUTOMATION",
                "auto-rule-1",
                "trigger",
                RuleBindingKind.DECISION_REF,
                null,
                new DecisionBinding(
                        "approval_routing",
                        DecisionVersionPolicy.ROLLOUT,
                        null,
                        null,
                        null,
                        List.of(new DecisionBinding.InputMapping(
                                "amount",
                                RuleValueSource.field(Scope.RECORD, "data.amount"))),
                        List.of(new DecisionBinding.OutputMapping(
                                "route",
                                new RuleMappingTarget(RuleMappingTarget.Kind.ACTION_PARAM, "route"))),
                        DecisionBinding.FallbackPolicy.failClosed(),
                        200,
                        DecisionBinding.TraceMode.ALWAYS,
                        true,
                        RuleValueSource.field(Scope.RECORD, "data.recordPid"),
                        null),
                true));
        automation.setTriggerConfig(cfg);
        return automation;
    }

    @Test
    void noDecisionRef_returnsPayloadUnchanged() {
        AutomationTriggerServiceImpl s = service();
        Map<String, Object> payload = Map.of("amount", 100);
        assertThat(s.withDecision(automation(null), payload)).isSameAs(payload);
    }

    @Test
    void withDecisionRef_injectsDecisionVariable() {
        when(decisionService.evaluate(any(DrtEvaluateRequest.class))).thenReturn(
                DecisionResult.builder("routing").status(DecisionStatus.MATCHED).matched(true)
                        .outputs(Map.of("route", "DIRECTOR")).build());
        AutomationTriggerServiceImpl s = service();

        Map<String, Object> enriched = s.withDecision(automation("routing"), Map.of("amount", 20000));
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) enriched.get("decision");
        assertThat(decision).containsEntry("matched", true).containsEntry("status", "MATCHED");
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) decision.get("outputs");
        assertThat(outputs).containsEntry("route", "DIRECTOR");
        assertThat(enriched).containsEntry("amount", 20000); // original payload preserved
    }

    @Test
    void withRuleBinding_injectsDecisionVariableViaRuleEvaluationService() {
        when(ruleEvaluationService.evaluateDecisionBinding(any(DecisionBinding.class), any(RuleEvaluationContext.class)))
                .thenReturn(new RuleEvaluationTrace(
                        "decision-trace-1",
                        "AUTOMATION",
                        "auto-rule-1",
                        "trigger",
                        RuleBindingKind.DECISION_REF,
                        "approval_routing",
                        2,
                        DecisionVersionPolicy.ROLLOUT,
                        null,
                        DecisionStatus.MATCHED,
                        true,
                        Map.of("amount", 20000),
                        Map.of("route", "DIRECTOR"),
                        false,
                        5,
                        null,
                        List.of(),
                        List.of(),
                        List.of("record.data.amount", "record.data.recordPid"),
                        List.of("approval_routing")));
        AutomationTriggerServiceImpl s = service();

        Map<String, Object> enriched = s.withDecision(
                automationWithRuleBinding(),
                Map.of("record", Map.of("amount", 20000, "recordPid", "REC-1")));

        ArgumentCaptor<RuleEvaluationContext> context = ArgumentCaptor.forClass(RuleEvaluationContext.class);
        verify(ruleEvaluationService).evaluateDecisionBinding(any(DecisionBinding.class), context.capture());
        assertThat(context.getValue().consumerType()).isEqualTo("AUTOMATION");
        assertThat(context.getValue().consumerCode()).isEqualTo("auto-rule-1");
        assertThat(context.getValue().toWireContext()).containsKey("record");

        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) enriched.get("decision");
        assertThat(decision)
                .containsEntry("matched", true)
                .containsEntry("status", "MATCHED")
                .containsEntry("traceId", "decision-trace-1")
                .containsEntry("fallbackApplied", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) decision.get("outputs");
        assertThat(outputs).containsEntry("route", "DIRECTOR");
    }

    @Test
    void withRuleBinding_propagatesMetaVirtualSourcesIntoRuleContext() {
        when(ruleEvaluationService.evaluateDecisionBinding(any(DecisionBinding.class), any(RuleEvaluationContext.class)))
                .thenReturn(new RuleEvaluationTrace(
                        "decision-trace-virtual",
                        "AUTOMATION",
                        "auto-rule-1",
                        "trigger",
                        RuleBindingKind.DECISION_REF,
                        "approval_routing",
                        2,
                        DecisionVersionPolicy.ROLLOUT,
                        null,
                        DecisionStatus.MATCHED,
                        true,
                        Map.of(),
                        Map.of("route", "DIRECTOR"),
                        false,
                        5,
                        null,
                        List.of(),
                        List.of(),
                        List.of("record.data.slaRiskScore"),
                        List.of("approval_routing")));
        AutomationTriggerServiceImpl s = service();
        List<Map<String, Object>> virtualSources = List.of(Map.of(
                "sourceRef", "virtual.leave_request_summary.v1",
                "recordId", "REQ-1"));

        s.withDecision(
                automationWithRuleBinding(),
                Map.of(
                        "record", Map.of("amount", 20000, "recordPid", "REQ-1"),
                        "meta", Map.of("virtualSources", virtualSources)));

        ArgumentCaptor<RuleEvaluationContext> context = ArgumentCaptor.forClass(RuleEvaluationContext.class);
        verify(ruleEvaluationService).evaluateDecisionBinding(any(DecisionBinding.class), context.capture());
        assertThat(context.getValue().toWireContext().get(Scope.META.code()))
                .containsEntry("virtualSources", virtualSources);
    }

    @Test
    void decisionFailure_degradesToErrorMarker_notCrash() {
        when(decisionService.evaluate(any(DrtEvaluateRequest.class)))
                .thenThrow(new RuntimeException("decision not found"));
        AutomationTriggerServiceImpl s = service();

        Map<String, Object> enriched = s.withDecision(automation("missing"), Map.of("x", 1));
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) enriched.get("decision");
        assertThat(decision).containsEntry("matched", false).containsEntry("status", "ERROR");
        assertThat(decision).containsKey("error");
    }

    @Test
    void noDecisionService_returnsPayloadUnchanged() {
        AutomationTriggerServiceImpl s = new AutomationTriggerServiceImpl(null, null, null); // service null
        Map<String, Object> payload = Map.of("a", 1);
        assertThat(s.withDecision(automation("routing"), payload)).isSameAs(payload);
    }
}
