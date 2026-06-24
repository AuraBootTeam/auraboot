package com.auraboot.framework.permission.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

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
 * @param recordId    internal target record ID (nullable for non-record operations)
 * @param recordPid   public target record PID (nullable for non-record operations)
 * @param finalResult whether the action is ultimately allowed
 * @param steps       ordered list of evaluation steps showing each layer's verdict
 */
public record PermissionExplanation(
        Long memberId,
        String resource,
        String action,
        @JsonIgnore
        @Schema(hidden = true)
        Long recordId,
        String recordPid,
        boolean finalResult,
        List<EvaluationStep> steps
) {
    public PermissionExplanation(Long memberId, String resource, String action,
                                 Long recordId, boolean finalResult,
                                 List<EvaluationStep> steps) {
        this(memberId, resource, action, recordId,
                recordId != null ? String.valueOf(recordId) : null,
                finalResult, steps);
    }
}
