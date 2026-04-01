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
     * Set a data scope for a specific role/resource/action combination (upsert).
     */
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
