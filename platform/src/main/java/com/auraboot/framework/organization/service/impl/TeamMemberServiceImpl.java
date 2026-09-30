package com.auraboot.framework.organization.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.organization.dto.TeamMemberAddRequest;
import com.auraboot.framework.organization.dto.TeamMemberResponse;
import com.auraboot.framework.organization.entity.Team;
import com.auraboot.framework.organization.entity.TeamMember;
import com.auraboot.framework.organization.mapper.TeamMapper;
import com.auraboot.framework.organization.mapper.TeamMemberMapper;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.organization.service.TeamGovernanceService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TeamMemberServiceImpl extends ServiceImpl<TeamMemberMapper, TeamMember> implements TeamMemberService {

    @Autowired
    private TeamMemberMapper teamMemberMapper;

    @Autowired
    private TeamMapper teamMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private TeamGovernanceService teamGovernanceService;

    @Autowired
    private com.auraboot.framework.meta.service.ActivityService activityService;

    @Override
    public List<TeamMemberResponse> listMembers(String teamPid) {
        Team team = teamGovernanceService.requireTeam(MetaContext.getCurrentTenantId(), teamPid);

        List<TeamMember> members = teamMemberMapper.findByTeamId(team.getId());
        return members.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional
    @CacheEvict(value = "dataScopeCondition", cacheManager = "permissionCacheManager", allEntries = true)
    public TeamMemberResponse addMember(String teamPid, TeamMemberAddRequest request, Long operatorId) {
        Team team = teamGovernanceService.requireTeam(MetaContext.getCurrentTenantId(), teamPid);
        teamGovernanceService.assertMemberRoleCanBeAdded(request.getRole());

        Long userId = resolveUserId(request);

        // Check if already a member
        TeamMember existing = teamMemberMapper.findByTeamIdAndUserId(team.getId(), userId);
        if (existing != null) {
            throw new BusinessException("User is already a member of this team");
        }

        // Verify user exists
        User user = userService.findByUserId(userId);
        if (user == null) {
            throw new BusinessException("User not found: " + userId);
        }

        TeamMember member = new TeamMember();
        member.setPid(UniqueIdGenerator.generate());
        member.setTenantId(MetaContext.getCurrentTenantId());
        member.setTeamId(team.getId());
        member.setUserId(userId);
        member.setRole(request.getRole() != null ? request.getRole() : "member");
        member.setJoinedAt(Instant.now());
        member.setCreatedAt(Instant.now());
        member.setUpdatedAt(Instant.now());
        member.setCreatedBy(operatorId);
        member.setUpdatedBy(operatorId);
        save(member);

        recordMembershipActivity(team, member, "team_member_added",
                "Added member to team " + team.getName());

        log.info("Member added to team: teamPid={}, userId={}, role={}", teamPid, userId, member.getRole());
        return toResponse(member);
    }

    private Long resolveUserId(TeamMemberAddRequest request) {
        if (request.getUserId() != null) {
            ensureTenantMember(request.getUserId());
            return request.getUserId();
        }
        if (request.getUserPid() != null && !request.getUserPid().isBlank()) {
            User user = userService.findByPid(request.getUserPid());
            if (user == null) {
                throw new BusinessException("User not found: " + request.getUserPid());
            }
            ensureTenantMember(user.getId());
            return user.getId();
        }
        if (request.getMemberPid() != null && !request.getMemberPid().isBlank()) {
            TenantMember member = tenantMemberService.findByPid(request.getMemberPid());
            if (member == null) {
                throw new BusinessException("Tenant member not found: " + request.getMemberPid());
            }
            Long tenantId = MetaContext.getCurrentTenantId();
            if (!tenantId.equals(member.getTenantId())) {
                throw new BusinessException("Tenant member not found: " + request.getMemberPid());
            }
            return member.getUserId();
        }
        throw new BusinessException("User ID, user pid or member pid is required");
    }

    private void ensureTenantMember(Long userId) {
        TenantMember tenantMember = tenantMemberService.findByTenantIdAndUserId(
                MetaContext.getCurrentTenantId(), userId);
        if (tenantMember == null) {
            throw new BusinessException("User is not a member of current tenant: " + userId);
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = "dataScopeCondition", cacheManager = "permissionCacheManager", allEntries = true)
    public void removeMember(String teamPid, String memberPid) {
        Team team = teamGovernanceService.requireTeam(MetaContext.getCurrentTenantId(), teamPid);

        QueryWrapper<TeamMember> qw = new QueryWrapper<>();
        qw.eq("pid", memberPid)
          .eq("team_id", team.getId());
        TeamMember member = getOne(qw);
        if (member == null) {
            throw new BusinessException("Team member not found: " + memberPid);
        }

        teamGovernanceService.assertCanRemoveMembership(team, member);
        removeById(member.getId());
        recordMembershipActivity(team, member, "team_member_removed",
                "Removed member from team " + team.getName());
        log.info("Member removed from team: teamPid={}, memberPid={}", teamPid, memberPid);
    }

    /**
     * X03-04 audit parity ("成员变更走审计,与 DEPT 同轨"): team membership changes
     * bypass the command pipeline, so the timeline entry the dynamic-model track
     * gets for free is recorded here explicitly. Must never break the member flow.
     */
    private void recordMembershipActivity(Team team, TeamMember member, String activityType, String subject) {
        try {
            activityService.recordSystemActivity(
                    MetaContext.getCurrentTenantId(),
                    "ab_team",
                    team.getPid(),
                    activityType,
                    subject,
                    null,
                    activityType.endsWith("_removed") ? "delete" : "create",
                    member.getUserId(),
                    null,
                    Map.of("teamPid", String.valueOf(team.getPid()),
                            "memberPid", String.valueOf(member.getPid()),
                            "memberRole", String.valueOf(member.getRole()))
            );
        } catch (Exception e) {
            log.warn("Failed to record team membership activity: {}", e.getMessage());
        }
    }

    @Override
    public List<String> getTeamPidsByUserId(Long userId, Long tenantId) {
        List<Long> teamIds = teamMemberMapper.findTeamIdsByUserIdAndTenantId(userId, tenantId);
        if (teamIds.isEmpty()) {
            return List.of();
        }

        QueryWrapper<Team> qw = new QueryWrapper<>();
        qw.in("id", teamIds).select("pid");
        return teamMapper.selectList(qw).stream()
                .map(Team::getPid)
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getTeamMembershipsByUserId(Long userId, Long tenantId) {
        List<Long> teamIds = teamMemberMapper.findTeamIdsByUserIdAndTenantId(userId, tenantId);
        if (teamIds.isEmpty()) {
            return List.of();
        }

        QueryWrapper<Team> teamQw = new QueryWrapper<>();
        teamQw.in("id", teamIds);
        List<Team> teams = teamMapper.selectList(teamQw);

        // Build membership info by joining team + member role
        return teams.stream().map(team -> {
            Map<String, Object> info = new HashMap<>();
            info.put("teamPid", team.getPid());
            info.put("teamName", team.getName());
            info.put("teamCode", team.getCode());
            // Resolve user's role and join time in this team
            TeamMember member = teamMemberMapper.findByTeamIdAndUserId(team.getId(), userId);
            info.put("role", member != null ? member.getRole() : "member");
            info.put("joinedAt", member != null ? member.getJoinedAt() : null);
            return info;
        }).collect(Collectors.toList());
    }

    private TeamMemberResponse toResponse(TeamMember member) {
        TeamMemberResponse resp = new TeamMemberResponse();
        resp.setPid(member.getPid());
        resp.setUserId(member.getUserId());
        resp.setRole(member.getRole());
        resp.setJoinedAt(member.getJoinedAt());

        // Resolve user info
        try {
            User user = userService.findByUserId(member.getUserId());
            if (user != null) {
                resp.setUserPid(user.getPid());
                resp.setUserName(user.getNickName() != null ? user.getNickName() : user.getUserName());
                resp.setUserEmail(user.getEmail());
            }
            TenantMember tenantMember = tenantMemberService.findByTenantIdAndUserId(member.getTenantId(), member.getUserId());
            if (tenantMember != null) {
                resp.setMemberPid(tenantMember.getPid());
            }
        } catch (Exception e) {
            log.debug("Failed to resolve user info for userId={}", member.getUserId());
        }

        return resp;
    }
}
