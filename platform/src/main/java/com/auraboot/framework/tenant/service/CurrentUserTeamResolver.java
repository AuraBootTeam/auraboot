package com.auraboot.framework.tenant.service;

import java.util.List;
import java.util.Map;

/**
 * Resolves team memberships for current user under current tenant context.
 */
public interface CurrentUserTeamResolver {

    /**
     * Resolve current user's team IDs.
     *
     * @return team IDs, never null
     */
    List<String> resolveCurrentUserTeamIds();

    /**
     * Resolve current user's team memberships with role information.
     * Returns a list of maps, each containing at least:
     * - teamPid: the team's pid
     * - teamName: the team's name
     * - role: user's role in the team (e.g., OWNER, MEMBER)
     *
     * @return team membership details, never null
     */
    default List<Map<String, Object>> resolveCurrentUserTeamMemberships() {
        // Default: return teamIds as simple maps for backward compatibility
        return resolveCurrentUserTeamIds().stream()
                .map(id -> Map.<String, Object>of("teamPid", id))
                .toList();
    }
}
