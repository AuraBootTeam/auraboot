package com.auraboot.framework.permission.service;

import java.util.Map;

/**
 * Permission Policy Service
 *
 * <p>Manages parameterized permission policies — configurable limits and constraints
 * beyond boolean granted/denied (e.g., max approval amount, discount limits).
 *
 * <p>Policy values are stored in {@code ab_role_permission.conditions} JSONB.
 * Policy schema (what parameters are configurable) is stored in {@code ab_permission.policy_schema} JSONB.
 *
 * @author AuraBoot Platform
 * @since V4
 */
public interface PermissionPolicyService {

    /**
     * Get effective policy for a member on a specific permission, merged across all roles.
     *
     * <p>Merge rules across multiple roles:
     * <ul>
     *   <li>Numeric max* fields — take MAX value (most permissive)</li>
     *   <li>Numeric min* fields — take MIN value (most permissive)</li>
     *   <li>Array/list fields — take UNION</li>
     *   <li>Boolean fields — OR (any role granting = true)</li>
     * </ul>
     *
     * @param memberId       member (tenant member) ID
     * @param permissionCode permission code (e.g. "model.order.approve")
     * @return merged policy map, or null if no policy configured
     */
    Map<String, Object> getEffectivePolicy(Long memberId, String permissionCode);

    /**
     * Get policy schema for a permission (what parameters are configurable).
     *
     * @param permissionCode permission code
     * @return policy schema map parsed from ab_permission.policy_schema, or null if not defined
     */
    Map<String, Object> getPolicySchema(String permissionCode);

    /**
     * Set policy values for a role+permission combination.
     *
     * <p>Stores values in {@code ab_role_permission.conditions} JSONB.
     *
     * @param roleId       role ID
     * @param permissionId permission ID
     * @param policyValues policy parameter values
     */
    void setPolicy(Long roleId, Long permissionId, Map<String, Object> policyValues);

    /**
     * Get policy values for a role+permission combination.
     *
     * @param roleId       role ID
     * @param permissionId permission ID
     * @return policy values map, or null if not configured
     */
    Map<String, Object> getPolicy(Long roleId, Long permissionId);

    /**
     * Batch get all policy values for a role, keyed by permission ID.
     * Single SQL query replaces N per-permission lookups.
     *
     * @param roleId role ID
     * @return map of permissionId -> policy values, empty map if none
     */
    Map<Long, Map<String, Object>> getPoliciesByRoleId(Long roleId);
}
