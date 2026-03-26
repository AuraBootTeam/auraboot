package com.auraboot.framework.organization.service;

import com.auraboot.framework.organization.dto.TeamMemberAddRequest;
import com.auraboot.framework.organization.dto.TeamMemberResponse;
import com.auraboot.framework.organization.entity.TeamMember;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

public interface TeamMemberService extends IService<TeamMember> {

    List<TeamMemberResponse> listMembers(String teamPid);

    TeamMemberResponse addMember(String teamPid, TeamMemberAddRequest request, Long operatorId);

    void removeMember(String teamPid, String memberPid);

    List<String> getTeamPidsByUserId(Long userId, Long tenantId);

    /**
     * Get team memberships with details (pid, name, role) for a user.
     */
    List<Map<String, Object>> getTeamMembershipsByUserId(Long userId, Long tenantId);
}
