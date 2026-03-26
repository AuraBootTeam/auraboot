package com.auraboot.framework.organization.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.organization.dto.*;
import com.auraboot.framework.organization.entity.Team;
import com.auraboot.framework.organization.entity.TeamMember;
import com.auraboot.framework.organization.mapper.TeamMapper;
import com.auraboot.framework.organization.mapper.TeamMemberMapper;
import com.auraboot.framework.organization.service.TeamService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

@Slf4j
@Service
public class TeamServiceImpl extends ServiceImpl<TeamMapper, Team> implements TeamService {

    @Autowired
    private TeamMapper teamMapper;

    @Autowired
    private TeamMemberMapper teamMemberMapper;

    @Autowired
    private UserService userService;

    @Override
    public List<TeamResponse> listTeams(Long tenantId) {
        QueryWrapper<Team> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId)
          .orderByAsc("code");
        return list(qw).stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    public TeamResponse getTeamByPid(String pid) {
        Team team = teamMapper.findByPid(pid);
        if (team == null) {
            throw new BusinessException("Team not found: " + pid);
        }
        return toResponse(team);
    }

    @Override
    @Transactional
    public TeamResponse createTeam(TeamCreateRequest request, Long tenantId, Long userId) {
        // Check code uniqueness
        Team existing = teamMapper.findByTenantIdAndCode(tenantId, request.getCode());
        if (existing != null) {
            throw new BusinessException("Team code already exists: " + request.getCode());
        }

        Team team = new Team();
        team.setPid(UniqueIdGenerator.generate());
        team.setTenantId(tenantId);
        team.setCode(request.getCode());
        team.setName(request.getName());
        team.setDescription(request.getDescription());
        team.setLeaderId(request.getLeaderId());
        team.setStatus(StatusConstants.ACTIVE);
        team.setCreatedBy(userId);
        team.setUpdatedBy(userId);
        team.setCreatedAt(Instant.now());
        team.setUpdatedAt(Instant.now());
        save(team);

        log.info("Team created: code={}, name={}, tenantId={}", team.getCode(), team.getName(), tenantId);
        return toResponse(team);
    }

    @Override
    @Transactional
    public TeamResponse updateTeam(String pid, TeamUpdateRequest request, Long userId) {
        Team team = teamMapper.findByPid(pid);
        if (team == null) {
            throw new BusinessException("Team not found: " + pid);
        }

        if (request.getName() != null) {
            team.setName(request.getName());
        }
        if (request.getDescription() != null) {
            team.setDescription(request.getDescription());
        }
        if (request.getLeaderId() != null) {
            team.setLeaderId(request.getLeaderId());
        }
        if (request.getStatus() != null) {
            team.setStatus(request.getStatus());
        }
        team.setUpdatedBy(userId);
        team.setUpdatedAt(Instant.now());
        updateById(team);

        return toResponse(team);
    }

    @Override
    @Transactional
    public void deleteTeam(String pid) {
        Team team = teamMapper.findByPid(pid);
        if (team == null) {
            throw new BusinessException("Team not found: " + pid);
        }

        // Remove all members first
        QueryWrapper<TeamMember> memberQw = new QueryWrapper<>();
        memberQw.eq("team_id", team.getId());
        teamMemberMapper.delete(memberQw);

        // Soft delete the team
        removeById(team.getId());
        log.info("Team deleted: code={}, pid={}", team.getCode(), pid);
    }

    @Override
    public List<TeamResponse> getCurrentUserTeams(Long userId, Long tenantId) {
        List<TeamMember> memberships = teamMemberMapper.findByUserIdAndTenantId(userId, tenantId);
        if (memberships.isEmpty()) {
            return List.of();
        }

        List<Long> teamIds = memberships.stream()
                .map(TeamMember::getTeamId)
                .collect(Collectors.toList());

        QueryWrapper<Team> qw = new QueryWrapper<>();
        qw.in("id", teamIds)
          .orderByAsc("code");
        return list(qw).stream().map(this::toResponse).collect(Collectors.toList());
    }

    private TeamResponse toResponse(Team team) {
        TeamResponse resp = new TeamResponse();
        resp.setPid(team.getPid());
        resp.setCode(team.getCode());
        resp.setName(team.getName());
        resp.setDescription(team.getDescription());
        resp.setLeaderId(team.getLeaderId());
        resp.setStatus(team.getStatus());
        resp.setCreatedAt(team.getCreatedAt());
        resp.setUpdatedAt(team.getUpdatedAt());

        // Resolve leader name
        if (team.getLeaderId() != null) {
            try {
                User leader = userService.findByPid(team.getLeaderId());
                if (leader != null) {
                    resp.setLeaderName(leader.getNickName() != null ? leader.getNickName() : leader.getUserName());
                }
            } catch (Exception e) {
                log.debug("Failed to resolve leader name for pid={}", team.getLeaderId());
            }
        }

        // Count members
        QueryWrapper<TeamMember> memberQw = new QueryWrapper<>();
        memberQw.eq("team_id", team.getId());
        Long count = teamMemberMapper.selectCount(memberQw);
        resp.setMemberCount(count != null ? count.intValue() : 0);

        return resp;
    }
}
