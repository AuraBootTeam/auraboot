package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Replay UI MVP — list-row projection of {@code ab_agent_run}.
 *
 * <p>Used by {@link com.auraboot.framework.agent.controller.AgentRunController}
 * paginated list endpoint. Each row joins LEFT against {@code ab_agent_bif}
 * to surface the upstream intent without forcing the user to drill into the
 * detail view for context.
 *
 * <p>Field mapping:
 * <ul>
 *   <li>{@code runId}        ← {@code ab_agent_run.pid}</li>
 *   <li>{@code runStatus}    ← {@code ab_agent_run.run_status}</li>
 *   <li>{@code parentRunId}  ← {@code ab_agent_run.parent_run_id}</li>
 *   <li>{@code subtaskOrigin}← {@code ab_agent_run.subtask_origin}</li>
 *   <li>{@code costUsd}      ← {@code ab_agent_run.total_cost}</li>
 *   <li>{@code durationMs}   ← stored {@code duration_ms} when present, else
 *       {@code completed_at - created_at} fallback</li>
 *   <li>{@code intentSummary}← {@code ab_agent_bif.intent} (first matching row
 *       on {@code run_id}, MIN-aggregated for stability)</li>
 * </ul>
 */
@Data
@Builder
public class AgentRunListItem {

    private String runId;
    private String agentCode;
    private String runStatus;
    private String parentRunId;
    private String subtaskOrigin;
    private BigDecimal costUsd;
    private Long durationMs;
    private Instant createdAt;
    private Instant completedAt;
    private String intentSummary;
}
