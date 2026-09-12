package com.auraboot.framework.behavior.dto;

import lombok.Data;

/** Database facts for one UTC first-use cohort and observation day. */
@Data
public class AnalyticsRetentionPoint {
    private String cohortDay;
    private int dayOffset;
    private long cohortSize;
    private long returningCount;
    private boolean mature;
}
