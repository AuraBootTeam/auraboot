package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.DecisionVersionPolicy;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleEvaluationService;
import com.auraboot.framework.decision.rule.RuleEvaluationTrace;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.policy.PolicyExpressionEvaluator;
import com.auraboot.framework.permission.engine.policy.PolicyViolation;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyEvaluatorTest {

    @Mock private PermissionPolicyService policyService;
    @Mock private PolicyExpressionEvaluator expressionEvaluator;
    @Mock private ObjectProvider<RuleEvaluationService> ruleEvaluationServiceProvider;
    @Mock private RuleEvaluationService ruleEvaluationService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private PolicyEvaluator evaluator;

    @Test
    void noPolicyReturnsNotApplicable() {
        when(policyService.getEffectivePolicy(1L, "M:edit")).thenReturn(Map.of());
        EvaluationStep s = evaluator.evaluate(1L, "M", "edit", null);
        assertEquals(EvaluationVerdict.NOT_APPLICABLE, s.verdict());
    }

    @Test
    void nullPolicyReturnsNotApplicable() {
        when(policyService.getEffectivePolicy(1L, "M:edit")).thenReturn(null);
        EvaluationStep s = evaluator.evaluate(1L, "M", "edit", null);
        assertEquals(EvaluationVerdict.NOT_APPLICABLE, s.verdict());
    }

    @Test
    void policyWithNoRecordReturnsAllow() {
        when(policyService.getEffectivePolicy(1L, "M:edit")).thenReturn(Map.of("maxAmount", 100));
        EvaluationStep s = evaluator.evaluate(1L, "M", "edit", null);
        assertEquals(EvaluationVerdict.ALLOW, s.verdict());
    }

    @Test
    void recordViolationDenies() {
        when(policyService.getEffectivePolicy(1L, "M:edit")).thenReturn(Map.of("maxAmount", 100));
        when(policyService.getPolicySchema("M:edit")).thenReturn(Map.of("maxAmount", Map.of("operator", "<=", "field", "amount")));
        when(expressionEvaluator.evaluate(eq("maxAmount"), any(), eq(100), any()))
                .thenReturn(new PolicyViolation("maxAmount", "amount exceeds 100"));

        EvaluationStep s = evaluator.evaluate(1L, "M", "edit", Map.of("amount", 200));
        assertEquals(EvaluationVerdict.DENY, s.verdict());
        assertTrue(s.reason().contains("amount exceeds 100"));
    }

    @Test
    void recordPassesAllChecksAllows() {
        when(policyService.getEffectivePolicy(1L, "M:edit")).thenReturn(Map.of("maxAmount", 100));
        when(policyService.getPolicySchema("M:edit")).thenReturn(null);
        when(expressionEvaluator.evaluate(any(), any(), any(), any())).thenReturn(null);

        EvaluationStep s = evaluator.evaluate(1L, "M", "edit", Map.of("amount", 50));
        assertEquals(EvaluationVerdict.ALLOW, s.verdict());
    }

    @Test
    void schemaWithNonMapRuleFallsBackToEmpty() {
        when(policyService.getEffectivePolicy(1L, "M:edit")).thenReturn(Map.of("maxAmount", 100));
        when(policyService.getPolicySchema("M:edit")).thenReturn(Map.of("maxAmount", "not-a-map"));
        when(expressionEvaluator.evaluate(any(), any(), any(), any())).thenReturn(null);

        EvaluationStep s = evaluator.evaluate(1L, "M", "edit", Map.of("amount", 50));
        assertEquals(EvaluationVerdict.ALLOW, s.verdict());
    }

    @Test
    void decisionAbacPolicyDeniesWhenDecisionDoesNotMatch() {
        Map<String, Object> binding = Map.of(
                "decisionCode", "permission_amount_guard",
                "versionPolicy", "LATEST_PUBLISHED",
                "timeoutMs", 50,
                "fallbackPolicy", Map.of("mode", "FAIL_CLOSED"));
        when(policyService.getEffectivePolicy(1L, "M:edit"))
                .thenReturn(Map.of("dynamicAbac", Map.of("decisionBinding", binding)));
        when(policyService.getPolicySchema("M:edit")).thenReturn(null);
        when(ruleEvaluationServiceProvider.getIfAvailable()).thenReturn(ruleEvaluationService);
        when(ruleEvaluationService.evaluateDecisionBinding(any(DecisionBinding.class), any()))
                .thenReturn(trace("permission_amount_guard", false, 5, false, null));

        EvaluationStep step = evaluator.evaluate(1L, "M", "edit", Map.of("amount", 200));

        assertEquals(EvaluationVerdict.DENY, step.verdict());
        assertTrue(step.reason().contains("permission_amount_guard"));
    }

    @Test
    void decisionAbacPolicyAllowsAndCachesDuplicateDecisionWithinOneEvaluation() {
        Map<String, Object> binding = Map.of(
                "decisionCode", "permission_department_guard",
                "versionPolicy", "LATEST_PUBLISHED",
                "timeoutMs", 50);
        when(policyService.getEffectivePolicy(1L, "M:view"))
                .thenReturn(Map.of(
                        "abacA", Map.of("decisionBinding", binding),
                        "abacB", Map.of("decisionBinding", binding)));
        when(policyService.getPolicySchema("M:view")).thenReturn(null);
        when(ruleEvaluationServiceProvider.getIfAvailable()).thenReturn(ruleEvaluationService);
        when(ruleEvaluationService.evaluateDecisionBinding(any(DecisionBinding.class), any()))
                .thenReturn(trace("permission_department_guard", true, 5, false, null));

        EvaluationStep step = evaluator.evaluate(1L, "M", "view", Map.of("departmentId", "D1"));

        assertEquals(EvaluationVerdict.ALLOW, step.verdict());
        verify(ruleEvaluationService).evaluateDecisionBinding(any(DecisionBinding.class), any());
    }

    @Test
    void decisionAbacPolicyFailsClosedWhenRuleRuntimeUnavailable() {
        when(policyService.getEffectivePolicy(1L, "M:delete"))
                .thenReturn(Map.of("dynamicAbac", Map.of(
                        "decisionBinding", Map.of("decisionCode", "permission_delete_guard"))));
        when(policyService.getPolicySchema("M:delete")).thenReturn(null);
        when(ruleEvaluationServiceProvider.getIfAvailable()).thenReturn(null);

        EvaluationStep step = evaluator.evaluate(1L, "M", "delete", Map.of("ownerId", "u1"));

        assertEquals(EvaluationVerdict.DENY, step.verdict());
        assertTrue(step.reason().contains("Rule center runtime unavailable"));
    }

    @Test
    void decisionAbacPolicyDeniesWhenEvaluationExceedsBindingTimeout() {
        when(policyService.getEffectivePolicy(1L, "M:approve"))
                .thenReturn(Map.of("dynamicAbac", Map.of(
                        "decisionBinding", Map.of(
                                "decisionCode", "permission_timeout_guard",
                                "timeoutMs", 1))));
        when(policyService.getPolicySchema("M:approve")).thenReturn(null);
        when(ruleEvaluationServiceProvider.getIfAvailable()).thenReturn(ruleEvaluationService);
        when(ruleEvaluationService.evaluateDecisionBinding(any(DecisionBinding.class), any()))
                .thenReturn(trace("permission_timeout_guard", true, 25, false, null));

        EvaluationStep step = evaluator.evaluate(1L, "M", "approve", Map.of("amount", 200));

        assertEquals(EvaluationVerdict.DENY, step.verdict());
        assertTrue(step.reason().contains("timed out"));
    }

    @Test
    void decisionAbacPolicyDeniesFallbackTraceEvenWhenFallbackIsFailOpen() {
        when(policyService.getEffectivePolicy(1L, "M:update"))
                .thenReturn(Map.of("dynamicAbac", Map.of(
                        "decisionBinding", Map.of(
                                "decisionCode", "permission_fallback_guard",
                                "fallbackPolicy", Map.of("mode", "FAIL_OPEN")))));
        when(policyService.getPolicySchema("M:update")).thenReturn(null);
        when(ruleEvaluationServiceProvider.getIfAvailable()).thenReturn(ruleEvaluationService);
        when(ruleEvaluationService.evaluateDecisionBinding(any(DecisionBinding.class), any()))
                .thenReturn(trace("permission_fallback_guard", true, 5, true, "DECISION_EVALUATION_FAILED"));

        EvaluationStep step = evaluator.evaluate(1L, "M", "update", Map.of("amount", 200));

        assertEquals(EvaluationVerdict.DENY, step.verdict());
        assertTrue(step.reason().contains("fallback"));
    }

    private RuleEvaluationTrace trace(String decisionCode, boolean matched, long durationMs,
                                      boolean fallbackApplied, String errorCode) {
        return new RuleEvaluationTrace(
                "trace-1",
                "PERMISSION",
                "M:edit",
                "Policy",
                RuleBindingKind.DECISION_REF,
                decisionCode,
                1,
                DecisionVersionPolicy.LATEST_PUBLISHED,
                null,
                errorCode == null ? DecisionStatus.MATCHED : DecisionStatus.ERROR,
                matched,
                Map.of(),
                Map.of(),
                fallbackApplied,
                durationMs,
                errorCode,
                errorCode == null ? java.util.List.of() : java.util.List.of(errorCode),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(decisionCode));
    }
}
