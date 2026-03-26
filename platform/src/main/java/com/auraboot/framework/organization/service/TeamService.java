package com.auraboot.framework.organization.service;

import com.auraboot.framework.organization.dto.*;
import com.auraboot.framework.organization.entity.Team;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface TeamService extends IService<Team> {

    List<TeamResponse> listTeams(Long tenantId);

    TeamResponse getTeamByPid(String pid);

    TeamResponse createTeam(TeamCreateRequest request, Long tenantId, Long userId);

    TeamResponse updateTeam(String pid, TeamUpdateRequest request, Long userId);

    void deleteTeam(String pid);

    List<TeamResponse> getCurrentUserTeams(Long userId, Long tenantId);
}
