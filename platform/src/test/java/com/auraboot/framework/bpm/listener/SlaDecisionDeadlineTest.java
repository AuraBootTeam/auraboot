package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.bpm.entity.SlaConfigEntity;
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
    void computeDeadlinePrefersRuleConsumerBindingWhenPresent() {
        when(ruleEvaluationService.evaluateDecisionBinding(any(DecisionBinding.class), any(RuleEvaluationContext.class)))
                .thenReturn(new RuleEvaluationTrace(
                        "decision-trace-sla",
                        "SLA",
                        "sla-binding-1",
                        "approve_task",
                        RuleBindingKind.DECISION_REF,
                        "complaint_sla_deadline",
                        1,
                        DecisionVersionPolicy.LATEST_PUBLISHED,
                        null,
                        DecisionStatus.MATCHED,
                        true,
                        Map.of("targetKey", "approve_task"),
                        Map.of("deadlineMinutes", 30),
                        false,
                        2,
                        null,
                        List.of(),
                        List.of(),
                        List.of("record.data.targetKey"),
                        List.of("complaint_sla_deadline")));
        SlaConfigEntity cfg = config();
        cfg.setPid("sla-binding-1");
        cfg.setDeadlineMode("FIXED");
        cfg.setDeadlineValue("PT24H");
        cfg.setRuleBinding(new RuleConsumerBinding(
                "SLA",
                "sla-binding-1",
                "deadline",
                RuleBindingKind.DECISION_REF,
                null,
                new DecisionBinding(
                        "complaint_sla_deadline",
                        DecisionVersionPolicy.LATEST_PUBLISHED,
                        null,
                        null,
                        null,
                        List.of(new DecisionBinding.InputMapping(
                                "targetKey",
                                com.auraboot.framework.decision.rule.RuleValueSource.field(
                                        com.auraboot.framework.decision.ast.Scope.RECORD,
                                        "data.targetKey"))),
                        List.of(),
                        DecisionBinding.FallbackPolicy.failClosed(),
                        200,
                        DecisionBinding.TraceMode.SAMPLED,
                        true,
                        null,
                        null),
                true));

        java.time.Instant before = java.time.Instant.now();
        java.time.Instant deadline = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                listenerWithRuleEvaluation(), "computeDeadline", cfg);

        assertThat(deadline).isNotNull();
        assertThat(java.time.Duration.between(before, deadline).toMinutes()).isBetween(29L, 30L);
        ArgumentCaptor<DecisionBinding> binding = ArgumentCaptor.forClass(DecisionBinding.class);
        verify(ruleEvaluationService).evaluateDecisionBinding(binding.capture(), any(RuleEvaluationContext.class));
        assertThat(binding.getValue().decisionCode()).isEqualTo("complaint_sla_deadline");
    }

    @Test
    void recordCreateRuleDeadlinePropagatesRecordDataAndMetaVirtualSources() {
        when(ruleEvaluationService.evaluateDecisionBinding(any(DecisionBinding.class), any(RuleEvaluationContext.class)))
                .thenReturn(new RuleEvaluationTrace(
                        "decision-trace-sla-record",
                        "SLA",
                        "sla-record-binding-1",
                        "wd_leave_request",
                        RuleBindingKind.DECISION_REF,
                        "leave_sla_deadline",
                        3,
                        DecisionVersionPolicy.LATEST_PUBLISHED,
                        null,
                        DecisionStatus.MATCHED,
                        true,
                        Map.of("leaveDays", 7),
                        Map.of("deadlineMinutes", 60),
                        false,
                        4,
                        null,
                        List.of(),
                        List.of(),
                        List.of("record.data.wd_req_days", "meta.virtualSources"),
                        List.of("leave_sla_deadline")));
        SlaConfigEntity cfg = config();
        cfg.setPid("sla-record-binding-1");
        cfg.setTargetType("RECORD");
        cfg.setTargetKey("wd_leave_request");
        cfg.setModelCode("wd_leave_request");
        cfg.setRuleBinding(new RuleConsumerBinding(
                "SLA",
                "sla-record-binding-1",
                "deadline",
                RuleBindingKind.DECISION_REF,
                null,
                new DecisionBinding(
                        "leave_sla_deadline",
                        DecisionVersionPolicy.LATEST_PUBLISHED,
                        null,
                        null,
                        null,
                        List.of(new DecisionBinding.InputMapping(
                                "leaveDays",
                                RuleValueSource.field(Scope.RECORD, "data.wd_req_days"))),
                        List.of(),
                        DecisionBinding.FallbackPolicy.failClosed(),
                        200,
                        DecisionBinding.TraceMode.SAMPLED,
                        true,
                        null,
                        null),
                true));

        Map<String, Object> recordData = new java.util.LinkedHashMap<>();
        recordData.put("pid", "REQ-1");
        recordData.put("wd_req_days", 7);
        recordData.put("_meta", Map.of(
                "virtualSources", List.of(Map.of(
                        "sourceRef", "wd_leave_request.days",
                        "field", "wd_req_days"))));

        java.time.Instant deadline = ReflectionTestUtils.invokeMethod(
                listenerWithRuleEvaluation(), "computeDeadline", cfg, recordData);

        assertThat(deadline).isNotNull();
        ArgumentCaptor<RuleEvaluationContext> context = ArgumentCaptor.forClass(RuleEvaluationContext.class);
        verify(ruleEvaluationService).evaluateDecisionBinding(any(DecisionBinding.class), context.capture());
        RuleEvaluationContext captured = context.getValue();
        assertThat(captured.consumerType()).isEqualTo("SLA");
        assertThat(captured.resolvePath(RuleValueSource.field(Scope.RECORD, "data.wd_req_days")).value())
                .isEqualTo(7);
        assertThat(captured.resolvePath(RuleValueSource.field(Scope.RECORD, "data.targetKey")).value())
                .isEqualTo("wd_leave_request");
        assertThat(captured.resolvePath(RuleValueSource.field(Scope.RECORD, "data._meta")).present())
                .isFalse();
        assertThat(captured.resolvePath(RuleValueSource.field(Scope.META, "virtualSources")).present())
                .isTrue();
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
    void ruleEvaluationServiceFailureDoesNotFallbackToLegacyDecisionService() {
        when(ruleEvaluationService.evaluateDecisionBinding(any(DecisionBinding.class), any(RuleEvaluationContext.class)))
                .thenThrow(new RuntimeException("timeout"));
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
