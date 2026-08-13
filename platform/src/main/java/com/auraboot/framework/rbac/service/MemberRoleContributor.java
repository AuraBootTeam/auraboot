package com.auraboot.framework.rbac.service;

import java.util.List;

/**
 * Contributes effective role assignments from governed subject sources such as teams.
 *
 * <p>Implementations must enforce tenant isolation and return active role IDs only. Direct
 * member-role rows remain owned by {@link UserRoleService}; contributors are read-only projections.
 */
public interface MemberRoleContributor {

    List<Long> findRoleIds(Long memberId, Long tenantId);

    /**
     * Changes whenever this contributor's effective assignments for the member may have changed.
     */
    default String cacheDiscriminator(Long memberId, Long tenantId) {
        return "";
    }
}
