package com.auraboot.framework.decision.dto;

import lombok.Data;

/**
 * DecisionOps dashboard KPI summary.
 */
@Data
public class DecisionDashboardSummaryDTO {
    private long definitions;
    private long policies;
    private long evaluationsToday;
    private long matched;
    private long failed;
    private long retrying;
    private Long p95LatencyMs;
}
