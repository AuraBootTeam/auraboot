package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.time.Instant;

/**
 * Read-model row for rollout governance preview.
 *
 * <p>The current Decision Runtime stores versions but not rollout routing rules yet. This DTO
 * exposes baseline/candidate information without pretending that traffic splitting is active.</p>
 */
@Data
public class DecisionRolloutPolicyDTO {
    private String pid;
    private String decisionCode;
    private Integer baselineVersion;
    private Integer candidateVersion;
    private String status;
    private Double percentage;
    private String routingKeyExpr;
    private Instant startedAt;
    private Instant updatedAt;
}
