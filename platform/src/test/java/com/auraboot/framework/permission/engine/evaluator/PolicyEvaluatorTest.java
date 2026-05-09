package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.policy.PolicyExpressionEvaluator;
import com.auraboot.framework.permission.engine.policy.PolicyViolation;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyEvaluatorTest {

    @Mock private PermissionPolicyService policyService;
    @Mock private PolicyExpressionEvaluator expressionEvaluator;
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
}
