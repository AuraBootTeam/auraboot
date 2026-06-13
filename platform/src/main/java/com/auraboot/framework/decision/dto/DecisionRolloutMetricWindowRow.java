package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class DecisionRolloutMetricWindowRow {
    private Instant windowStart;
    private String rolloutArm;
    private Long evaluations;
    private Long matched;
    private Long errors;
    private Long p95LatencyMs;
}
