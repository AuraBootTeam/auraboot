package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import org.springframework.stereotype.Component;

/**
 * Policy evaluator — checks parameter limits and business rules.
 *
 * <p>Phase 1: STUB — always returns NOT_APPLICABLE.
 * <p>Phase 2+: Will evaluate ABAC-style policies (e.g. amount limits, time windows).
 */
@Component
public class PolicyEvaluator {

    private static final String NAME = "Policy";

    /**
     * Evaluate whether the operation satisfies policy constraints.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier
     * @param action   action identifier
     * @param record   the target record
     * @return evaluation step with NOT_APPLICABLE verdict (stub)
     */
    public EvaluationStep evaluate(Long memberId, String resource, String action, Object record) {
        return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE, "Not yet implemented");
    }
}
