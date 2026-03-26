package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.EvidenceSubmitRequest;
import com.auraboot.framework.meta.entity.DecisionRecord;
import com.auraboot.framework.meta.entity.EvidenceRecord;

import java.util.List;

/**
 * Adjudicator Service.
 * Collects evidence, evaluates invariants, and produces formal decisions.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
public interface AdjudicatorService {

    /**
     * Submit evidence for a subject at a specific stage.
     * If autoAdjudicate is enabled and all evidence collected, auto-produces decision.
     */
    EvidenceRecord submitEvidence(EvidenceSubmitRequest request);

    /**
     * Trigger adjudication manually.
     * Checks evidence completeness, evaluates invariants, produces decision.
     *
     * @return the decision record, or null if evidence incomplete
     */
    DecisionRecord adjudicate(Long tenantId, String subjectType, String subjectId,
                              String stage, String outcome, Long userId);

    /**
     * Get the decision for a subject at a stage.
     */
    DecisionRecord getDecision(Long tenantId, String subjectType, String subjectId, String stage);

    /**
     * Get all collected evidence for a subject at a stage.
     */
    List<EvidenceRecord> getEvidence(Long tenantId, String subjectType, String subjectId, String stage);

    /**
     * Check if all required evidence has been collected.
     */
    boolean isEvidenceComplete(Long tenantId, String subjectType, String subjectId, String stage);
}
