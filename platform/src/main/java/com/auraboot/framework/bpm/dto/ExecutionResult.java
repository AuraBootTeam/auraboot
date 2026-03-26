package com.auraboot.framework.bpm.dto;

import java.time.Instant;

/**
 * Result of starting or modifying a process execution.
 */
public record ExecutionResult(
        String executionId,
        String processKey,
        String state,
        Instant startedAt
) {}
