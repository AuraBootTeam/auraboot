package com.auraboot.framework.permission.engine.model;

import java.util.List;

/**
 * Audit/compliance model that explains WHY a permission decision was made.
 *
 * <p>Contains the full evaluation pipeline trace for a specific
 * member + resource + action + record combination.
 *
 * @param memberId    the member (user) being evaluated
 * @param resource    resource identifier (e.g. model code)
 * @param action      action identifier (e.g. "view", "create", "edit", "delete")
 * @param recordId    target record ID (nullable for non-record operations)
 * @param finalResult whether the action is ultimately allowed
 * @param steps       ordered list of evaluation steps showing each layer's verdict
 */
public record PermissionExplanation(
        Long memberId,
        String resource,
        String action,
        Long recordId,
        boolean finalResult,
        List<EvaluationStep> steps
) {
}
