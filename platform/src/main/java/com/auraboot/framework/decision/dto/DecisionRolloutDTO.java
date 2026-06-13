package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

@Data
public class DecisionRolloutDTO {
    private String pid;
    private String decisionCode;
    private Integer baselineVersion;
    private Integer candidateVersion;
    private String status;
    private Integer percentage;
    private JsonNode cohort;
    private JsonNode segment;
    private String routingKeyExpr;
    private String salt;
    private String startedBy;
    private Instant startedAt;
    private String endedBy;
    private Instant endedAt;
    private JsonNode audit;
    private Instant createdAt;
    private Instant updatedAt;
}
