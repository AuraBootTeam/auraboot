package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Replay UI MVP — single-row projection of {@code ab_agent_interrupt_log}.
 *
 * <p>Joined to a run on {@code active_run_id = ab_agent_run.pid}. Surfaced
 * inside {@link AgentRunDetail} so operators see whether an interrupt
 * triggered the run termination or spawned a child via {@code subtask_run_id}.
 */
@Data
@Builder
public class AgentInterruptItem {

    private String pid;
    private String sessionId;
    private String activeRunId;
    private String newMessageExcerpt;
    private String subPolicy;
    private String classifierTier;
    private BigDecimal confidence;
    private String reason;
    private String actionTaken;
    private String subtaskRunId;
    private Instant createdAt;
}
