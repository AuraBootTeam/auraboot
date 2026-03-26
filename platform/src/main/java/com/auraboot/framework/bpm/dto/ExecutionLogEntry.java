package com.auraboot.framework.bpm.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Single entry in the execution timeline.
 */
public record ExecutionLogEntry(
        String pid,
        String executionId,
        String nodeId,
        String nodeType,
        String eventType,
        Map<String, Object> inputData,
        Map<String, Object> outputData,
        String errorMessage,
        Long durationMs,
        Instant createdAt
) {}
