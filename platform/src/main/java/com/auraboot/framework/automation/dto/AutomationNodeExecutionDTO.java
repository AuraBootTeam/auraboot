package com.auraboot.framework.automation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Transport shape for one node execution row (G5).
 *
 * <p>Mirrors what the SDK {@code FlowDesigner} {@code nodeStatus} prop consumes —
 * the frontend flattens an array of these into {@code Record<nodeId, status>}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationNodeExecutionDTO {
    private String nodeId;
    private String status;
    private Instant startedAt;
    private Instant completedAt;
    private String errorMessage;
    private String processInstanceId;
}
