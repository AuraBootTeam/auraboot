package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.List;

/**
 * Blast-radius read model for condition fragment reuse.
 */
@Data
public class ConditionFragmentImpactDTO {
    private String fragmentCode;
    private int incomingCount;
    private List<DecisionImpactRefDTO> incoming;
}
