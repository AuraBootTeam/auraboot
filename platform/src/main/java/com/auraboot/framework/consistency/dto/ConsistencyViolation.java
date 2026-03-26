package com.auraboot.framework.consistency.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents a single consistency rule violation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsistencyViolation {

    private String ruleCode;
    private String ruleName;
    private String severity;
    private String message;
    private String sourceModel;
    private String targetModel;
    private BigDecimal sourceAggregatedValue;
    private BigDecimal targetValue;
}
