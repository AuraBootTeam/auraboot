package com.auraboot.framework.decision.service;

import com.auraboot.framework.decision.dto.DecisionImpactDTO;
import com.auraboot.framework.decision.dto.DecisionFieldImpactDTO;
import com.auraboot.framework.decision.dto.DecisionFieldPreflightDTO;
import com.auraboot.framework.decision.dto.DecisionFieldPreflightRequest;
import com.auraboot.framework.decision.dto.DecisionUsageIndexRebuildDTO;
import com.auraboot.framework.decision.dto.DecisionIntegrationImpactDTO;

/**
 * Builds the DecisionOps impact graph for a decision definition.
 */
public interface DecisionImpactService {

    DecisionImpactDTO getDecisionImpact(String decisionCode);

    DecisionFieldImpactDTO getFieldImpact(String fieldRef);

    /**
     * Batch variant of {@link #getFieldImpact(String)} for one governance sweep:
     * probes every ref first and, only when some probe comes back empty, runs a
     * single lazy rebuild instead of one per ref.
     */
    java.util.Map<String, DecisionFieldImpactDTO> getFieldImpacts(java.util.Collection<String> fieldRefs);

    DecisionFieldPreflightDTO preflightFieldChange(DecisionFieldPreflightRequest request);

    DecisionIntegrationImpactDTO getIntegrationImpact(String targetType, String targetCode);

    DecisionUsageIndexRebuildDTO rebuildUsageIndex();
}
