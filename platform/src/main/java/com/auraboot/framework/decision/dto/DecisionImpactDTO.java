package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.List;

/**
 * API-backed decision impact read model for blast-radius governance.
 */
@Data
public class DecisionImpactDTO {

    private String decisionCode;
    private List<DecisionImpactRefDTO> incoming;
    private List<DecisionImpactRefDTO> outgoing;
    private DecisionImpactRiskDTO risk;
}
