package com.auraboot.framework.eventpolicy.service;

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
     * Find all enabled definitions matching the given event_type + target_type + target_key
     * for the current tenant.
     */
    List<DrtPolicyDefinitionEntity> findByEventAndTarget(String eventType, String targetType, String targetKey);
}
