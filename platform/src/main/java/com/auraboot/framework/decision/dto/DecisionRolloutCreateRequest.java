package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DecisionRolloutCreateRequest {

    @NotNull
    private Integer baselineVersion;

    @NotNull
    private Integer candidateVersion;

    @Min(0)
    @Max(100)
    private Integer percentage = 0;

    private JsonNode cohort;

    private JsonNode segment;

    private String routingKeyExpr;

    private String salt;
}
