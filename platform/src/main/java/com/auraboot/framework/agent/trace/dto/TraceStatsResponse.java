package com.auraboot.framework.agent.trace.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TraceStatsResponse {
    private long totalTraces;
    private long successCount;
    private long errorCount;
    private double successRate;
    private Double avgDurationMs;
    private BigDecimal totalCost;
    private long totalInputTokens;
    private long totalOutputTokens;
}
