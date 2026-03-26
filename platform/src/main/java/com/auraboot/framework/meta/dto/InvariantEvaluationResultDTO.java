package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing the result of a single invariant evaluation.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvariantEvaluationResultDTO {

    private String invariantCode;

    private boolean passed;

    private String errorMessage;

    private long executionTimeMs;
}
