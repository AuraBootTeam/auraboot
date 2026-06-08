package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.bpm.entity.SlaConfigEntity;
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
 * M5 consumer integration — SLA deadlineMode=RULE computes the deadline minutes from a DecisionRuntime
 * decision (output deadlineMinutes). Unit-tests the package-private resolver (the additive seam);
 * degrades to null (caller falls back to default) when the decision is absent/non-matching/failing.
 * The full activation-listener end-to-end IT is a documented follow-on.
 */
class SlaDecisionDeadlineTest {

    private final DecisionEvaluationService decisionService = mock(DecisionEvaluationService.class);

    private SlaActivationListener listener() {
        SlaActivationListener l = new SlaActivationListener(null, null);
        ReflectionTestUtils.setField(l, "decisionEvaluationService", decisionService);
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
    void returnsNullWhenNotMatched() {
        when(decisionService.evaluate(any(DrtEvaluateRequest.class))).thenReturn(
                DecisionResult.builder("x").status(DecisionStatus.NOT_MATCHED).matched(false).build());
        assertThat(listener().resolveRuleDeadlineMinutes(config(), "x")).isNull();
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
