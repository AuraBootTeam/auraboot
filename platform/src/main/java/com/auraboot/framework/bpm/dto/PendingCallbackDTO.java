package com.auraboot.framework.bpm.dto;

import java.time.Instant;

/**
 * Information about a node waiting for an external callback.
 */
public record PendingCallbackDTO(
        String executionId,
        String nodeId,
        String nodeType,
        Instant waitingSince
) {}
