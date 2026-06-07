package com.auraboot.framework.eventpolicy.service;

import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.auraboot.framework.eventpolicy.model.MatchMode;

import java.util.List;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Service for the EventPolicy version lifecycle (DRAFT → VALIDATED → PUBLISHED).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface EventPolicyVersionService {

    /**
     * Create a new DRAFT version for the given policyCode.
     * version = max(existing versions) + 1 (or 1 if first).
     */
    DrtPolicyVersionEntity createDraft(String policyCode,
                                        PolicyPhase phase,
                                        MatchMode matchMode,
                                        ExecutionMode executionMode,
                                        FailureStrategy failureStrategy,
                                        ConflictStrategy conflictStrategy,
                                        DedupStrategy dedupStrategy,
                                        JsonNode rulesJson);

    /**
     * Validate a DRAFT version: deserialize rulesJson into List<PolicyRule>, verify each
     * rule's ConditionNode parses. Transitions DRAFT → VALIDATED on success.
     * Returns the updated entity.
     */
    DrtPolicyVersionEntity validate(String pid);

    /**
     * Publish a VALIDATED version: VALIDATED → PUBLISHED (per VersionStatus state machine).
     * Immutable once published; computes content_hash from rulesJson.
     * Returns the updated entity.
     */
    DrtPolicyVersionEntity publish(String pid);

    /**
     * Find a version by its pid (tenant-scoped).
     * Returns null if not found.
     */
    DrtPolicyVersionEntity findByPid(String pid);

    /**
     * List all versions for a given policyCode within the current tenant,
     * ordered by version number ascending.
     */
    List<DrtPolicyVersionEntity> listByCode(String policyCode);
}
