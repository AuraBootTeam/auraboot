package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import org.springframework.stereotype.Component;

/**
 * Data scope evaluator — checks data visibility scope.
 *
 * <p>Phase 1: STUB — always returns NOT_APPLICABLE / all().
 * <p>Phase 2+: Will resolve scope based on org hierarchy and role data-scope config.
 */
@Component
public class DataScopeEvaluator {

    private static final String NAME = "DataScope";

    /**
     * Evaluate whether the member can access the record based on data scope.
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

    /**
     * Get the SQL-level data scope condition for list queries.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier
     * @param action   action identifier
     * @return full access condition (stub)
     */
    public DataScopeCondition getCondition(Long memberId, String resource, String action) {
        return DataScopeCondition.all();
    }
}
