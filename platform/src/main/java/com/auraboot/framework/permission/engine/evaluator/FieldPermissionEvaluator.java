package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import org.springframework.stereotype.Component;

/**
 * Field permission evaluator — checks field-level visibility and editability.
 *
 * <p>Phase 1: STUB — always returns NOT_APPLICABLE.
 * <p>Phase 2+: Will determine which fields are visible/editable per role.
 */
@Component
public class FieldPermissionEvaluator {

    private static final String NAME = "FieldPermission";

    /**
     * Evaluate field-level permissions for the given record.
     *
     * <p>Note: Field permissions do not affect the grant/deny decision,
     * they only record which fields should be masked or read-only.
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
