package com.auraboot.framework.agent.service;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Snapshot of a child {@code ab_agent_run} terminal-state result returned
 * by {@link ParentJoinService#joinChildRun(String, String, long)}.
 *
 * <p>Mirrors the {@link ChildRunCompletedEvent} envelope (terminal label) plus
 * cost / token columns read from {@code ab_agent_run} at the moment the child
 * reached terminal. All fields are non-null; {@code totalCost} defaults to
 * {@link BigDecimal#ZERO} when the DB column is NULL (legacy rows seeded
 * before cost tracking landed).
 *
 * @param childRunId      child {@code ab_agent_run.pid}
 * @param terminalStatus  one of {@code "succeeded"}, {@code "cancelled"},
 *                        {@code "failed"} (lowercase, mirrors
 *                        {@link ChildRunCompletedEvent#getOutcome()})
 * @param inputTokens     {@code ab_agent_run.input_tokens} ≥ 0
 * @param outputTokens    {@code ab_agent_run.output_tokens} ≥ 0
 * @param totalCost       {@code ab_agent_run.total_cost} as BigDecimal; never null
 */
public record ChildRunOutcome(
        String childRunId,
        String terminalStatus,
        long inputTokens,
        long outputTokens,
        BigDecimal totalCost) {

    public ChildRunOutcome {
        Objects.requireNonNull(childRunId, "childRunId");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        Objects.requireNonNull(totalCost, "totalCost");
    }
}
