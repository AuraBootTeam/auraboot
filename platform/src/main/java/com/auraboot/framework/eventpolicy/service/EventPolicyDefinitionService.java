package com.auraboot.framework.eventpolicy.service;

import com.auraboot.framework.eventpolicy.dto.EventPolicyDefinitionSummary;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyDefinitionEntity;

import java.util.List;

/**
 * Service for managing EventPolicy definitions (the logical catalogue entry).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface EventPolicyDefinitionService {

    /**
     * Create a new policy definition (tenant-scoped via MetaContext).
     * Throws if policy_code already exists for the tenant.
     */
    DrtPolicyDefinitionEntity create(String policyCode, String policyName,
                                     String eventType, String targetType, String targetKey);

    /**
     * Find a policy definition by its unique policyCode within the current tenant.
     * Returns null if not found.
     */
    DrtPolicyDefinitionEntity findByCode(String policyCode);

    /**
     * Enable or disable a policy definition.
     */
    DrtPolicyDefinitionEntity setEnabled(String policyCode, boolean enabled);

    /**
     * Copy a policy definition. If the source has a latest version, copy it as DRAFT v1.
     */
    DrtPolicyDefinitionEntity copy(String sourcePolicyCode, String newPolicyCode, String newPolicyName);

    /**
     * List policy definitions for the current tenant with optional console filters.
     */
    List<EventPolicyDefinitionSummary> listDefinitions(
            String keyword, String eventType, String targetType, String targetKey, String status);

    /**
     * Find all enabled definitions matching the given event_type + target_type + target_key
     * for the current tenant.
     */
    List<DrtPolicyDefinitionEntity> findByEventAndTarget(String eventType, String targetType, String targetKey);
}
