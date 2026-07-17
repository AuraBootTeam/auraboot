package com.auraboot.framework.permission.service;

import java.util.List;
import java.util.Map;

/**
 * Permission Policy Service
 *
 * <p>Manages parameterized permission policies — configurable limits and constraints
 * beyond boolean granted/denied (e.g., max approval amount, discount limits).
 *
 * <p>Policy values are stored in {@code ab_role_permission.conditions} JSONB (legacy config
 * surface, still consumed by the permission matrix UI and policy admin APIs). The runtime
 * guard, however, reads the materialized {@code ab_role_permission.condition_ast} JSONB via
 * {@link #getConditionGuards} — see Permission Governance S1 Plan B.
 *
 * @author AuraBoot Platform
 * @since V4
 */
public interface PermissionPolicyService {

    /**
     * A single condition-AST guard attached to one active grant binding.
     *
     * @param grantId          ab_role_permission.id
     * @param conditionAstJson materialized {@code condition_ast} as raw JSON, or {@code null}
     *                         when no materialized guard exists
     * @param conditionsJson   legacy/config {@code conditions} as raw JSON. The runtime only
     *                         treats recognized Rule Center bindings as guards; unrelated legacy
     *                         policy values remain config-only.
     */
    record ConditionGuard(
            Long grantId,
            String conditionAstJson,
            String conditionsJson,
            String validationError) {

        public ConditionGuard(Long grantId, String conditionAstJson) {
            this(grantId, conditionAstJson, null, null);
        }

        public ConditionGuard(Long grantId, String conditionAstJson, String conditionsJson) {
            this(grantId, conditionAstJson, conditionsJson, null);
        }

        /** An unconditional grant always satisfies the guard layer. */
        public boolean unconditional() {
            return conditionAstJson == null || conditionAstJson.isBlank()
                    || "null".equals(conditionAstJson.trim());
        }

        public boolean hasConditionAst() {
            return !unconditional();
        }
    }

    /**
     * Permission Governance S1 (Plan B): load the materialized condition-AST guards for every
     * active GRANT binding a member holds on a permission.
     *
     * <p>This replaces the legacy {@code getEffectivePolicy} + {@code getPolicySchema} read path
     * inside the runtime evaluator. The returned guards are evaluated under three-valued logic;
     * see {@code PolicyEvaluator}.
     *
     * @param memberId       member (tenant member) ID
     * @param permissionCode permission code (e.g. "model.order:approve")
     * @return one guard per active grant binding (possibly empty; never null)
     */
    List<ConditionGuard> getConditionGuards(Long memberId, String permissionCode);

    /**
     * Get effective policy for a member on a specific permission, merged across all roles.
     *
     * <p>Legacy config read surface (still used by the permission matrix UI and policy facade).
     * NOT used by the runtime evaluator anymore — see {@link #getConditionGuards}.
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
     * <p>Legacy config read surface (permission matrix UI). NOT used by the runtime evaluator.
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
