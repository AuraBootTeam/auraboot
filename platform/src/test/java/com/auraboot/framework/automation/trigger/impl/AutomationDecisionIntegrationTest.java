package com.auraboot.framework.automation.trigger.impl;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

    private AutomationTriggerServiceImpl service() {
        AutomationTriggerServiceImpl s = new AutomationTriggerServiceImpl(null, null, null);
        ReflectionTestUtils.setField(s, "decisionEvaluationService", decisionService);
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
