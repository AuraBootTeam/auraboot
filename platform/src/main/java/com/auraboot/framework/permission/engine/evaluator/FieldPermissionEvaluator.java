package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Field permission evaluator — checks field-level visibility and editability.
 *
 * <p>This evaluator does not affect the grant/deny decision for record access.
 * It only determines which fields should be visible or editable for the member.
 * It always returns ALLOW or NOT_APPLICABLE — never DENY.
 *
 * <p>The actual field filtering is applied post-query in DynamicDataService.
 */
@Component
@RequiredArgsConstructor
public class FieldPermissionEvaluator {

    private static final String NAME = "FieldPermission";

    private final FieldPermissionService fieldPermissionService;

    /**
     * Evaluate field-level permissions for the given resource.
     *
     * <p>Note: Field permissions do not affect the grant/deny decision,
     * they only record which fields should be masked or read-only.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier (model code)
     * @param action   action identifier
     * @param record   the target record (unused in field permission evaluation)
     * @return evaluation step with ALLOW or NOT_APPLICABLE verdict
     */
    public EvaluationStep evaluate(Long memberId, String resource, String action, Object record) {
        FieldPermissionSet fieldPerms = fieldPermissionService.getFieldPermissions(memberId, resource);

        if (fieldPerms.hiddenFields().isEmpty()) {
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE,
                    "No field restrictions — all fields accessible");
        }

        String reason = String.format(
                "Field permissions applied: viewable=%d, editable=%d, hidden=%d",
                fieldPerms.viewableFields().size(),
                fieldPerms.editableFields().size(),
                fieldPerms.hiddenFields().size());

        return new EvaluationStep(NAME, EvaluationVerdict.ALLOW, reason);
    }
}
