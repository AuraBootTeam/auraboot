package com.auraboot.framework.decision.dto;

import lombok.Data;

@Data
public class DecisionRolloutMetricDistributionRow {
    private String rolloutArm;
    private String resultKey;
    private Long itemCount;
}
