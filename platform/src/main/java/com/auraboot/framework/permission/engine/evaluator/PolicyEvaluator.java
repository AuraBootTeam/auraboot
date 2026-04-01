package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Policy evaluator — checks parameter limits and business rules.
 *
 * <p>Evaluates ABAC-style policies such as amount limits, discount caps, etc.
 * Policies are configured per role+permission in ab_role_permission.conditions JSONB,
 * and merged across roles using permissive rules (max for limits, OR for booleans).
 */
@Component
@RequiredArgsConstructor
public class PolicyEvaluator {

    private static final String NAME = "Policy";

    private final PermissionPolicyService policyService;

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
            List<String> violations = validateRecord(recordMap, policy);
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
     * <p>For max* policy keys, checks if the corresponding record field exceeds the limit.
     * The field name is derived by removing the "max" prefix and lowering the first char.
     * e.g., maxApprovalAmount -> approvalAmount
     */
    private List<String> validateRecord(Map<?, ?> record, Map<String, Object> policy) {
        List<String> violations = new ArrayList<>();

        for (Map.Entry<String, Object> entry : policy.entrySet()) {
            String key = entry.getKey();
            Object limit = entry.getValue();

            if (key.startsWith("max") && key.length() > 3 && limit instanceof Number maxVal) {
                // Derive field name: maxApprovalAmount -> approvalAmount
                String fieldKey = key.substring(3, 4).toLowerCase() + key.substring(4);
                Object recordVal = record.get(fieldKey);
                if (recordVal instanceof Number numVal && numVal.doubleValue() > maxVal.doubleValue()) {
                    violations.add(key + ": " + numVal + " exceeds limit " + maxVal);
                }
            }

            if (key.startsWith("min") && key.length() > 3 && limit instanceof Number minVal) {
                // Derive field name: minOrderQuantity -> orderQuantity
                String fieldKey = key.substring(3, 4).toLowerCase() + key.substring(4);
                Object recordVal = record.get(fieldKey);
                if (recordVal instanceof Number numVal && numVal.doubleValue() < minVal.doubleValue()) {
                    violations.add(key + ": " + numVal + " below minimum " + minVal);
                }
            }
        }

        return violations;
    }
}
