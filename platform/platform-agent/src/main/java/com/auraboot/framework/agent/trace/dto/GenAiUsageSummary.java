package com.auraboot.framework.agent.trace.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Aggregated LLM usage/cost for one model within a tenant (A-G6 analysis read).
 * Backs the cost view; the durable {@code ab_gen_ai_usage} ledger is the source.
 */
@Data
public class GenAiUsageSummary {
    private String model;
    private Long totalInputTokens;
    private Long totalOutputTokens;
    private BigDecimal totalAmount;
    private Long callCount;
}
