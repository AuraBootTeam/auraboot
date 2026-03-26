package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Invariant rule definition for decision adjudication.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvariantRuleDTO {

    /**
     * Invariant name (e.g. "credit_score_sufficient").
     */
    private String name;

    /**
     * SpEL expression to evaluate (e.g. "#evidence['credit_score'].score > 650").
     */
    private String expression;

    /**
     * Severity: ERROR (blocks decision) / WARN (logs alarm but allows decision).
     */
    private String severity;

    private String description;
}
