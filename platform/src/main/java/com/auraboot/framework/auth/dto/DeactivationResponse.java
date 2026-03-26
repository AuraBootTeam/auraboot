package com.auraboot.framework.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Response DTO for deactivation status.
 *
 * @since 7.1.0
 */
@Data
@Builder
public class DeactivationResponse {

    private String pid;
    private String status;
    private String reason;
    private Instant requestedAt;
    private Instant coolingOffUntil;
    private Instant cancelledAt;
    private Instant completedAt;
}
