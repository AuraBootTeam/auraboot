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
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Override
    public List<TeamMemberResponse> listMembers(String teamPid) {
        Team team = teamMapper.findByPid(teamPid);
        if (team == null) {
            throw new BusinessException("Team not found: " + teamPid);
        }

        List<TeamMember> members = teamMemberMapper.findByTeamId(team.getId());
        return members.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public TeamMemberResponse addMember(String teamPid, TeamMemberAddRequest request, Long operatorId) {
        Team team = teamMapper.findByPid(teamPid);
        if (team == null) {
            throw new BusinessException("Team not found: " + teamPid);
        }

        // Check if already a member
        TeamMember existing = teamMemberMapper.findByTeamIdAndUserId(team.getId(), request.getUserId());
        if (existing != null) {
            throw new BusinessException("User is already a member of this team");
        }

        // Verify user exists
        User user = userService.findByUserId(request.getUserId());
        if (user == null) {
            throw new BusinessException("User not found: " + request.getUserId());
        }

        TeamMember member = new TeamMember();
        member.setPid(UniqueIdGenerator.generate());
        member.setTenantId(MetaContext.getCurrentTenantId());
        member.setTeamId(team.getId());
        member.setUserId(request.getUserId());
        member.setRole(request.getRole() != null ? request.getRole() : "member");
        member.setJoinedAt(Instant.now());
        member.setCreatedAt(Instant.now());
        member.setUpdatedAt(Instant.now());
        member.setCreatedBy(operatorId);
        member.setUpdatedBy(operatorId);
        save(member);

        log.info("Member added to team: teamPid={}, userId={}, role={}", teamPid, request.getUserId(), member.getRole());
        return toResponse(member);
    }

    @Override
    @Transactional
    public void removeMember(String teamPid, String memberPid) {
        Team team = teamMapper.findByPid(teamPid);
        if (team == null) {
            throw new BusinessException("Team not found: " + teamPid);
        }

        QueryWrapper<TeamMember> qw = new QueryWrapper<>();
        qw.eq("pid", memberPid)
          .eq("team_id", team.getId());
        TeamMember member = getOne(qw);
        if (member == null) {
            throw new BusinessException("Team member not found: " + memberPid);
        }

        removeById(member.getId());
        log.info("Member removed from team: teamPid={}, memberPid={}", teamPid, memberPid);
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
                resp.setUserName(user.getNickName() != null ? user.getNickName() : user.getUserName());
                resp.setUserEmail(user.getEmail());
            }
        } catch (Exception e) {
            log.debug("Failed to resolve user info for userId={}", member.getUserId());
        }

        return resp;
    }
}
