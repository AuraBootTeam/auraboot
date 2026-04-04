package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.policy.PolicyExpressionEvaluator;
import com.auraboot.framework.permission.engine.policy.PolicyViolation;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Policy evaluator -- checks parameter limits and business rules.
 *
 * <p>Evaluates ABAC-style policies such as amount limits, discount caps, etc.
 * Policies are configured per role+permission in ab_role_permission.conditions JSONB,
 * and merged across roles using permissive rules (max for limits, OR for booleans).
 *
 * <p>Supports two evaluation modes:
 * <ul>
 *   <li><b>Expression-based</b>: when policy_schema defines operator + field, delegates
 *       to {@link PolicyExpressionEvaluator} for operator evaluation</li>
 *   <li><b>Legacy convention</b>: when no schema or no operator defined, uses
 *       maxXxx/minXxx naming convention</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PolicyEvaluator {

    private static final String NAME = "Policy";

    private final PermissionPolicyService policyService;
    private final PolicyExpressionEvaluator expressionEvaluator;

    /**
     * Evaluate whether the operation satisfies policy constraints.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier
     * @param action   action identifier
     * @param record   the target record (Map for field-level validation)
     * @return evaluation step with verdict
     */
    public EvaluationStep evaluate(Long memberId, String resource, String action, Object record) {
        String permissionCode = resource + ":" + action;
        Map<String, Object> policy = policyService.getEffectivePolicy(memberId, permissionCode);

        if (policy == null || policy.isEmpty()) {
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE, "No policy configured");
        }

        // If record is provided, validate against policy constraints
        if (record instanceof Map<?, ?> recordMap) {
            Map<String, Object> policySchema = policyService.getPolicySchema(permissionCode);
            List<String> violations = validateRecord(recordMap, policy, policySchema);
            if (!violations.isEmpty()) {
                return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                        "Policy violations: " + String.join(", ", violations));
            }
        }

        return new EvaluationStep(NAME, EvaluationVerdict.ALLOW, "Policy satisfied");
    }

    /**
     * Validate a record against policy constraints.
     *
     * <p>For each policy key:
     * <ol>
     *   <li>If policySchema defines a rule with operator + field for this key,
     *       delegate to {@link PolicyExpressionEvaluator}</li>
     *   <li>Otherwise, fall back to legacy maxXxx/minXxx naming convention</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private List<String> validateRecord(Map<?, ?> record, Map<String, Object> policy,
                                         Map<String, Object> policySchema) {
        List<String> violations = new ArrayList<>();

        for (Map.Entry<String, Object> entry : policy.entrySet()) {
            String key = entry.getKey();
            Object policyValue = entry.getValue();

            // Try expression-based evaluation if schema defines this rule
            Map<String, Object> rule = getSchemaRule(policySchema, key);

            PolicyViolation violation = expressionEvaluator.evaluate(key, rule, policyValue, record);
            if (violation != null) {
                violations.add(violation.message());
            }
        }

        return violations;
    }

    /**
     * Get the schema rule definition for a given policy key.
     * Returns an empty map if no schema or no rule defined (triggers legacy evaluation).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getSchemaRule(Map<String, Object> policySchema, String key) {
        if (policySchema != null) {
            Object ruleDef = policySchema.get(key);
            if (ruleDef instanceof Map<?, ?> ruleMap) {
                return (Map<String, Object>) ruleMap;
            }
        }
        // No schema or no rule for this key -- return empty map to trigger legacy evaluation
        return Map.of();
    }
}
