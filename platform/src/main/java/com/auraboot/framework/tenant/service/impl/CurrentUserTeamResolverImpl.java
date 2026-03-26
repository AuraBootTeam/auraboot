package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Team resolver backed by ab_team + ab_team_member platform tables.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrentUserTeamResolverImpl implements CurrentUserTeamResolver {

    private final TeamMemberService teamMemberService;

    @Override
    public List<String> resolveCurrentUserTeamIds() {
        if (!MetaContext.exists()) {
            return List.of();
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            return List.of();
        }

        return teamMemberService.getTeamPidsByUserId(userId, tenantId);
    }

    @Override
    public List<Map<String, Object>> resolveCurrentUserTeamMemberships() {
        if (!MetaContext.exists()) {
            return List.of();
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            return List.of();
        }

        return teamMemberService.getTeamMembershipsByUserId(userId, tenantId);
    }
}
