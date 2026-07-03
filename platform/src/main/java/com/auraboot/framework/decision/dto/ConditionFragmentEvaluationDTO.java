package com.auraboot.framework.decision.dto;

import com.auraboot.framework.decision.ast.EvalTrace;
import lombok.Data;

/**
 * Evaluation result for a reusable condition fragment.
 */
@Data
public class ConditionFragmentEvaluationDTO {
    private String fragmentCode;
    private Integer version;
    private String result;
    private boolean matched;
    private EvalTrace trace;
}
