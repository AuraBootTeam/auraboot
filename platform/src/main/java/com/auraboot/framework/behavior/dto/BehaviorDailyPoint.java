package com.auraboot.framework.behavior.dto;

import lombok.Data;

/**
 * One day's behavior rollup (M1 analysis: time series for the UV/PV dashboard).
 * {@code day} is YYYY-MM-DD (UTC day bucket of occurred_at, falling back to received).
 */
@Data
public class BehaviorDailyPoint {
    private String day;
    private Long totalEvents;
    private Long pageViews;
    private Long uniqueVisitors;
}
