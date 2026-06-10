package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.List;

/**
 * API-backed field impact read model from the Decision Runtime usage index.
 */
@Data
public class DecisionFieldImpactDTO {

    private String fieldRef;
    private List<DecisionImpactRefDTO> references;
    private DecisionImpactRiskDTO risk;
}
