package com.auraboot.framework.bpm.dto;

import java.util.List;

/**
 * Detailed execution history for a specific node.
 */
public record NodeExecutionDetail(
        String nodeId,
        String nodeType,
        List<ExecutionLogEntry> events,
        String latestStatus,
        Long totalDurationMs
) {}
