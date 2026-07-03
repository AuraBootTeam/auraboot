package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.Map;

/**
 * Request body for evaluating a condition fragment against a supplied context.
 */
@Data
public class ConditionFragmentEvaluateRequest {
    private Map<String, Object> context;
}
