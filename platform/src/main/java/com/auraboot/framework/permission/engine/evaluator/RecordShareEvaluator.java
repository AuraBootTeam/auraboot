package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import org.springframework.stereotype.Component;

/**
 * ReBAC evaluator — checks record sharing exceptions.
 *
 * <p>Phase 1: STUB — always returns NOT_APPLICABLE.
 * <p>Phase 2+: Will check ab_record_share table for explicit sharing grants.
 */
@Component
public class RecordShareEvaluator {

    private static final String NAME = "RecordShare";

    /**
     * Evaluate whether the member has access to the record via sharing.
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
