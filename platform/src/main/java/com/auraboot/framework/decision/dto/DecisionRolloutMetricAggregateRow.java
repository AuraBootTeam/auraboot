package com.auraboot.framework.decision.dto;

import lombok.Data;

@Data
public class DecisionRolloutMetricAggregateRow {
    private String rolloutArm;
    private Long evaluations;
    private Long matched;
    private Long errors;
    private Long p95LatencyMs;
}
