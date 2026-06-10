package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.Map;

/**
 * Blast-radius summary for guarded publish/deprecate/retire actions.
 */
@Data
public class DecisionImpactRiskDTO {

    private Boolean blocking;
    private String summary;
    private Map<String, Integer> counts;
}
