package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.DecisionVersionPolicy;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleEvaluationContext;
import com.auraboot.framework.decision.rule.RuleEvaluationService;
import com.auraboot.framework.decision.rule.RuleEvaluationTrace;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M5 consumer integration — SLA deadlineMode=RULE computes the deadline minutes from a DecisionRuntime
 * decision (output deadlineMinutes). Unit-tests the package-private resolver (the additive seam);
 * degrades to null (caller falls back to default) when the decision is absent/non-matching/failing.
 * The full activation-listener end-to-end IT is a documented follow-on.
 */
class SlaDecisionDeadlineTest {

    private final DecisionEvaluationService decisionService = mock(DecisionEvaluationService.class);
    private final RuleEvaluationService ruleEvaluationService = mock(RuleEvaluationService.class);

    private SlaActivationListener listener() {
        SlaActivationListener l = new SlaActivationListener(null, null);
        ReflectionTestUtils.setField(l, "decisionEvaluationService", decisionService);
        return l;
    }

    private SlaActivationListener listenerWithRuleEvaluation() {
        SlaActivationListener l = listener();
        ReflectionTestUtils.setField(l, "ruleEvaluationService", ruleEvaluationService);
        return l;
    }

    private SlaConfigEntity config() {
        SlaConfigEntity c = new SlaConfigEntity();
        c.setTargetType("FORM");
        c.setTargetKey("complaint");
        return c;
    }

    private DecisionResult withMinutes(Object minutes) {
        return DecisionResult.builder("sla_deadline").status(DecisionStatus.MATCHED).matched(true)
                .outputs(minutes == null ? Map.of() : Map.of("deadlineMinutes", minutes)).build();
    }

    @Test
    void resolvesNumericDeadlineMinutes() {
        when(decisionService.evaluate(any(DrtEvaluateRequest.class))).thenReturn(withMinutes(120));
        assertThat(listener().resolveRuleDeadlineMinutes(config(), "sla_deadline")).isEqualTo(120L);
    }

    @Test
    void resolvesStringDeadlineMinutes() {
        when(decisionService.evaluate(any(DrtEvaluateRequest.class))).thenReturn(withMinutes("90"));
        assertThat(listener().resolveRuleDeadlineMinutes(config(), "sla_deadline")).isEqualTo(90L);
    }

    @Test
    void resolvesDeadlineMinutesThroughRuleEvaluationService() {
        when(ruleEvaluationService.evaluateDecisionBinding(any(DecisionBinding.class), any(RuleEvaluationContext.class)))
                .thenReturn(new RuleEvaluationTrace(
                        "decision-trace-sla",
                        "SLA",
                        "sla-1",
                        "complaint",
                        RuleBindingKind.DECISION_REF,
                        "sla_deadline",
                        1,
                        DecisionVersionPolicy.LATEST_PUBLISHED,
                        null,
                        DecisionStatus.MATCHED,
                        true,
                        Map.of("targetType", "FORM", "targetKey", "complaint"),
                        Map.of("deadlineMinutes", "45"),
                        false,
                        3,
                        null,
                        List.of(),
                        List.of(),
                        List.of("record.data.targetType", "record.data.targetKey"),
                        List.of("sla_deadline")));
        SlaConfigEntity cfg = config();
        cfg.setPid("sla-1");

        assertThat(listenerWithRuleEvaluation().resolveRuleDeadlineMinutes(cfg, "sla_deadline")).isEqualTo(45L);
        ArgumentCaptor<RuleEvaluationContext> context = ArgumentCaptor.forClass(RuleEvaluationContext.class);
        verify(ruleEvaluationService).evaluateDecisionBinding(any(DecisionBinding.class), context.capture());
        assertThat(context.getValue().consumerType()).isEqualTo("SLA");
        assertThat(context.getValue().consumerCode()).isEqualTo("sla-1");
    }

    @Test
    void returnsNullWhenNotMatched() {
        when(decisionService.evaluate(any(DrtEvaluateRequest.class))).thenReturn(
                DecisionResult.builder("x").status(DecisionStatus.NOT_MATCHED).matched(false).build());
        assertThat(listener().resolveRuleDeadlineMinutes(config(), "x")).isNull();
    }

    @Test
    void ruleEvaluationServicePresentDoesNotFallbackToLegacyDecisionService() {
        when(ruleEvaluationService.evaluateDecisionBinding(any(DecisionBinding.class), any(RuleEvaluationContext.class)))
                .thenReturn(new RuleEvaluationTrace(
                        "decision-trace-sla",
                        "SLA",
                        "sla-1",
                        "complaint",
                        RuleBindingKind.DECISION_REF,
                        "sla_deadline",
                        1,
                        DecisionVersionPolicy.LATEST_PUBLISHED,
                        null,
                        DecisionStatus.NOT_MATCHED,
                        false,
                        Map.of("targetType", "FORM", "targetKey", "complaint"),
                        Map.of(),
                        false,
                        3,
                        null,
                        List.of(),
                        List.of(),
                        List.of("record.data.targetType", "record.data.targetKey"),
                        List.of("sla_deadline")));
        when(decisionService.evaluate(any(DrtEvaluateRequest.class))).thenReturn(withMinutes(15));

        assertThat(listenerWithRuleEvaluation().resolveRuleDeadlineMinutes(config(), "sla_deadline")).isNull();
        verify(decisionService, never()).evaluate(any(DrtEvaluateRequest.class));
    }

    @Test
    void returnsNullWhenNoMinutesOutput() {
        when(decisionService.evaluate(any(DrtEvaluateRequest.class))).thenReturn(withMinutes(null));
        assertThat(listener().resolveRuleDeadlineMinutes(config(), "x")).isNull();
    }

    @Test
    void degradesToNullOnFailure_andWhenNoService() {
        when(decisionService.evaluate(any(DrtEvaluateRequest.class))).thenThrow(new RuntimeException("boom"));
        assertThat(listener().resolveRuleDeadlineMinutes(config(), "x")).isNull();

        SlaActivationListener noService = new SlaActivationListener(null, null);
        assertThat(noService.resolveRuleDeadlineMinutes(config(), "x")).isNull();
    }
}
