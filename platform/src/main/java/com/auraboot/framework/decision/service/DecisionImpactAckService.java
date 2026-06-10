package com.auraboot.framework.decision.service;

/**
 * Records explicit DecisionOps blast-radius acknowledgements.
 */
public interface DecisionImpactAckService {

    void recordAcknowledgement(
            String actionType,
            String targetType,
            String targetCode,
            String targetPid,
            String targetPath,
            String impactSummary,
            Object impactSnapshot,
            String note);
}
