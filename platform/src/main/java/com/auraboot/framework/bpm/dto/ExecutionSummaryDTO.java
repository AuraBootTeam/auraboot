package com.auraboot.framework.bpm.dto;

import java.time.Instant;

/**
 * Summary of a process execution.
 */
public record ExecutionSummaryDTO(
        String executionId,
        int totalNodes,
        int completedNodes,
        int failedNodes,
        long totalDurationMs,
        Instant startedAt,
        Instant completedAt
) {}
