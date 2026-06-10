package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.List;

/**
 * API-backed integration blast-radius read model for platform connectors/webhooks.
 */
@Data
public class DecisionIntegrationImpactDTO {

    private String targetType;
    private String targetCode;
    private String manageUrl;
    private List<DecisionImpactRefDTO> references;
    private DecisionImpactRiskDTO risk;
}
