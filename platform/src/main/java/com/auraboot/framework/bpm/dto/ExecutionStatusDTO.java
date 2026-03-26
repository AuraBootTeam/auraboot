package com.auraboot.framework.bpm.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full execution status including current state and node details.
 */
public record ExecutionStatusDTO(
        String executionId,
        String processKey,
        String state,
        String currentNodeId,
        Map<String, Object> variables,
        List<ExecutionLogEntry> recentEvents,
        Instant startedAt
) {}
