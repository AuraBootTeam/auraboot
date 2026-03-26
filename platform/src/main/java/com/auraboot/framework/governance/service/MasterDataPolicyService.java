package com.auraboot.framework.governance.service;

import com.auraboot.framework.governance.dto.PolicyCreateDTO;
import com.auraboot.framework.governance.dto.PolicyResponse;

import java.util.List;

/**
 * Service for managing master data governance policies.
 * Determines which models require approval and/or auto-snapshot.
 */
public interface MasterDataPolicyService {

    /**
     * Create or update a governance policy for a model.
     * If a policy already exists for the model, it is updated.
     */
    PolicyResponse upsertPolicy(PolicyCreateDTO dto, Long tenantId);

    /**
     * List all governance policies for a tenant.
     */
    List<PolicyResponse> listPolicies(Long tenantId);

    /**
     * Get policy for a specific model, or null if none.
     */
    PolicyResponse getPolicy(String modelCode, Long tenantId);

    /**
     * Delete a governance policy by PID.
     */
    void deletePolicy(String pid, Long tenantId);

    /**
     * Check if a model has a governance policy with requireApproval=true.
     */
    boolean requiresApproval(String modelCode, Long tenantId);

    /**
     * Check if a model has a governance policy with autoSnapshot=true.
     */
    boolean requiresAutoSnapshot(String modelCode, Long tenantId);
}
