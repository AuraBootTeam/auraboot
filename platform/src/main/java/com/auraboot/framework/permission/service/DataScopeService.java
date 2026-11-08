package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.entity.RoleDataScope;

import java.util.List;

/**
 * Service for resolving and managing data scope configurations.
 */
public interface DataScopeService {

    /**
     * Resolve the effective data scope condition for a member on a specific resource/action.
     * Merges across all roles assigned to the member.
     *
     * @param memberId     tenant member ID
     * @param resourceCode resource code (typically model code)
     * @param actionCode   action code (e.g. "read", "update")
     * @return the resolved data scope condition
     */
    DataScopeCondition resolveScope(Long memberId, String resourceCode, String actionCode);

    /**
     * Resolve only the role that supplied a business grant. The permission resource/action
     * identifies the configuration; the record model owns field/department/team metadata.
     * Missing/inactive membership, role, or scope denies. Never unions another role or share.
     */
    default DataScopeCondition resolveScopeForRole(Long memberId, Long roleId, String permissionResource,
                                                   String action, String recordModel) {
        return DataScopeCondition.none();
    }

    /** Explicit owner opt-in: retain an action override, otherwise use this role's object read scope. */
    default DataScopeCondition resolveObjectBoundScopeForRole(Long memberId, Long roleId, String permissionResource,
                                                              String action, String recordModel) {
        return DataScopeCondition.none();
    }

    /** Role-local ordinary read scope; implementations must qualify the owner before preserving legacy NULL. */
    default DataScopeCondition resolveOrdinaryReadScopeForRole(Long memberId, Long roleId, String recordModel) {
        return DataScopeCondition.none();
    }

    /** Role-local ordinary update scope; does not use another role's scope or manufacture ALL. */
    default DataScopeCondition resolveOrdinaryUpdateScopeForRole(Long memberId, Long roleId, String recordModel) {
        return DataScopeCondition.none();
    }

    /** Role-local ordinary create scope; qualified legacy defaults are not persisted as ALL. */
    default DataScopeCondition resolveOrdinaryCreateScopeForRole(Long memberId, Long roleId, String recordModel) {
        return DataScopeCondition.none();
    }

    /** Resolve current member-group scope without requiring a business-row team column. */
    default DataScopeCondition resolveAudienceScope(Long memberId, String resourceCode, String actionCode) {
        return resolveHistoricalScope(memberId, resourceCode, actionCode);
    }

    /** Resolve current historical scope without legacy ownership approximations. */
    default DataScopeCondition resolveHistoricalScope(Long memberId, String resourceCode, String actionCode) {
        return DataScopeCondition.none();
    }


    /**
     * Resolve whether an owner user PID currently belongs to one of the permitted departments.
     * This supports records such as CRM opportunities whose department is derived from their owner
     * instead of copied into a stale business-record snapshot.
     */
    boolean isOwnerInDepartments(String ownerUserPid, List<String> departmentPids);

    /** Resolve a standard numeric creator identity within the current tenant. */
    default boolean isCreatorInDepartments(Long userId, List<String> departmentPids) {
        return false;
    }


    /**
     * Set a data scope for a specific role/resource/action combination (upsert).
     */
    // Internal derivation primitive: management transports use PermissionMatrixService.setRoleScope.
    // This writer preserves tenant/role structure but does not grant administrative authority.
    void setScope(Long tenantId, Long roleId, String resourceCode, String actionCode,
                  String scopeType, String mergeStrategy);

    /**
     * Remove a data scope entry.
     */
    void removeScope(Long tenantId, Long roleId, String resourceCode, String actionCode);

    /**
     * Get all data scope entries for a role in a tenant.
     */
    List<RoleDataScope> getScopesByRole(Long tenantId, Long roleId);
}
