package com.auraboot.framework.decision.service;

import com.auraboot.framework.decision.dto.DecisionImpactRefDTO;
import com.auraboot.framework.decision.dto.DecisionUsageIndexRebuildDTO;

import java.util.List;

/**
 * Maintains and queries the Decision Runtime source -> target usage index.
 */
public interface DecisionUsageIndexService {

    DecisionUsageIndexRebuildDTO rebuild();

    DecisionUsageIndexRebuildDTO refreshDecisionVersion(String versionPid);

    DecisionUsageIndexRebuildDTO refreshSource(String sourceType, String sourcePid);

    DecisionUsageIndexRebuildDTO deleteSource(String sourceType, String sourcePid);

    List<DecisionImpactRefDTO> findIncomingDecisionRefs(String decisionCode);

    List<DecisionImpactRefDTO> findOutgoingDecisionRefs(String decisionCode);

    List<DecisionImpactRefDTO> findFieldRefs(String fieldRef);

    List<DecisionImpactRefDTO> findTargetRefs(String targetType, String targetCode);
}
