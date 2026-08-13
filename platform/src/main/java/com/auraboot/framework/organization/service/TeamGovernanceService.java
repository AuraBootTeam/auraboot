package com.auraboot.framework.organization.service;

import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.organization.entity.Team;
import com.auraboot.framework.organization.entity.TeamMember;
import com.auraboot.framework.organization.mapper.TeamMapper;
import com.auraboot.framework.organization.mapper.TeamMemberMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Enforces tenant scope and a single active leader for platform teams. */
@Service
@RequiredArgsConstructor
public class TeamGovernanceService {

    public static final String LEADER = "LEADER";
    public static final String MEMBER = "MEMBER";

    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final TenantMemberService tenantMemberService;
    private final UserService userService;

    public Team requireTeam(Long tenantId, String teamPid) {
        Team team = teamMapper.findByTenantIdAndPid(tenantId, teamPid);
        if (team == null) {
            throw new BusinessException("Team not found: " + teamPid);
        }
        return team;
    }

    @Transactional
    public void synchronizeLeader(Team team, String leaderUserPid, Long operatorId) {
        if (leaderUserPid == null || leaderUserPid.isBlank()) {
            if (team.getLeaderId() != null) {
                demoteLeader(team, team.getLeaderId(), operatorId);
            }
            team.setLeaderId(null);
            return;
        }

        User leader = userService.findByPid(leaderUserPid.trim());
        TenantMember tenantMember = leader == null ? null
                : tenantMemberService.findByTenantIdAndUserId(team.getTenantId(), leader.getId());
        if (tenantMember == null || !StatusConstants.ACTIVE.equalsIgnoreCase(tenantMember.getStatus())) {
            throw new BusinessException("Team leader must be an active member of the same tenant");
        }

        if (team.getLeaderId() != null && !team.getLeaderId().equals(leader.getPid())) {
            demoteLeader(team, team.getLeaderId(), operatorId);
        }
        TeamMember membership = teamMemberMapper.findByTeamIdAndUserId(team.getId(), leader.getId());
        if (membership == null) {
            membership = new TeamMember();
            membership.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
            membership.setPid(UniqueIdGenerator.generate());
            membership.setTenantId(team.getTenantId());
            membership.setTeamId(team.getId());
            membership.setUserId(leader.getId());
            membership.setRole(LEADER);
            membership.setJoinedAt(Instant.now());
            membership.setCreatedAt(Instant.now());
            membership.setUpdatedAt(Instant.now());
            membership.setCreatedBy(operatorId);
            membership.setUpdatedBy(operatorId);
            teamMemberMapper.insert(membership);
        } else if (!LEADER.equalsIgnoreCase(membership.getRole())) {
            teamMemberMapper.updateRole(membership.getId(), team.getTenantId(), LEADER, operatorId);
        }
        team.setLeaderId(leader.getPid());
    }

    public void assertCanRemoveMembership(Team team, TeamMember membership) {
        if (membership != null && LEADER.equalsIgnoreCase(membership.getRole())) {
            throw new BusinessException("Transfer team leadership before removing the current leader");
        }
    }

    public void assertMemberRoleCanBeAdded(String requestedRole) {
        if (LEADER.equalsIgnoreCase(requestedRole)) {
            throw new BusinessException("Assign the team leader through the team governance endpoint");
        }
    }

    private void demoteLeader(Team team, String leaderUserPid, Long operatorId) {
        User previous = userService.findByPid(leaderUserPid);
        if (previous == null) {
            return;
        }
        TeamMember membership = teamMemberMapper.findByTeamIdAndUserId(team.getId(), previous.getId());
        if (membership != null) {
            teamMemberMapper.updateRole(membership.getId(), team.getTenantId(), MEMBER, operatorId);
        }
    }
}
