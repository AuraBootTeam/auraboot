package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Replay UI MVP — single-row projection of {@code ab_agent_action}.
 *
 * <p>JSONB columns ({@code before_snapshot}, {@code after_snapshot},
 * {@code field_changes}) are returned as raw JSON strings; the frontend
 * pretty-prints them in the diff drawer. Returning them as parsed objects
 * would force every dependant DTO to specify a JSONB type-handler — the
 * Replay UI is read-only and never round-trips this data back into
 * structured Java.
 */
@Data
@Builder
public class AgentActionItem {

    private String pid;
    private String resultContractId;
    private Integer stepIndex;
    private Integer toolCallIndex;

    private String actionCode;
    private String actionType;
    private String intentSummary;
    private String targetModel;
    private String targetRecordId;
    private String targetRecordPid;

    private String beforeSnapshot;   // raw JSON string (JSONB)
    private String afterSnapshot;    // raw JSON string (JSONB)
    private String fieldChanges;     // raw JSON string (JSONB)

    private String commandCode;
    private String commandResult;

    private String riskLevel;
    private String estimatedRisk;
    private Boolean riskDeviation;
    private String reversalMode;

    private String actionStatus;
    private String errorMessage;

    private BigDecimal costUsd;
    private Integer tokenUsage;

    private String fidelity;
    private String skillCode;

    private String parallelGroupId;
    private Integer parallelIndex;

    private Instant executedAt;
}
