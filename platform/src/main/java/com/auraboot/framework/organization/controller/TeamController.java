package com.auraboot.framework.organization.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.organization.dto.*;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.organization.service.TeamService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/org/teams")
@Tag(name = "Teams", description = "Team management")
public class TeamController {

    @Autowired
    private TeamService teamService;

    @Autowired
    private TeamMemberService teamMemberService;

    @GetMapping
    public ApiResponse<List<TeamResponse>> listTeams() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(teamService.listTeams(tenantId));
    }

    @GetMapping("/{pid}")
    public ApiResponse<TeamResponse> getTeam(@PathVariable String pid) {
        return ApiResponse.success(teamService.getTeamByPid(pid));
    }

    @PostMapping
    public ApiResponse<TeamResponse> createTeam(
            @Valid @RequestBody TeamCreateRequest request,
            @CurrentUserId Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(teamService.createTeam(request, tenantId, userId));
    }

    @PutMapping("/{pid}")
    public ApiResponse<TeamResponse> updateTeam(
            @PathVariable String pid,
            @Valid @RequestBody TeamUpdateRequest request,
            @CurrentUserId Long userId) {
        return ApiResponse.success(teamService.updateTeam(pid, request, userId));
    }

    @DeleteMapping("/{pid}")
    public ApiResponse<Boolean> deleteTeam(@PathVariable String pid) {
        teamService.deleteTeam(pid);
        return ApiResponse.success(true);
    }

    // --- Team Members ---

    @GetMapping("/{teamPid}/members")
    public ApiResponse<List<TeamMemberResponse>> listMembers(@PathVariable String teamPid) {
        return ApiResponse.success(teamMemberService.listMembers(teamPid));
    }

    @PostMapping("/{teamPid}/members")
    public ApiResponse<TeamMemberResponse> addMember(
            @PathVariable String teamPid,
            @Valid @RequestBody TeamMemberAddRequest request,
            @CurrentUserId Long userId) {
        return ApiResponse.success(teamMemberService.addMember(teamPid, request, userId));
    }

    @DeleteMapping("/{teamPid}/members/{memberPid}")
    public ApiResponse<Boolean> removeMember(
            @PathVariable String teamPid,
            @PathVariable String memberPid) {
        teamMemberService.removeMember(teamPid, memberPid);
        return ApiResponse.success(true);
    }

    // --- Current user teams ---

    @GetMapping("/current-user")
    public ApiResponse<List<TeamResponse>> getCurrentUserTeams(@CurrentUserId Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(teamService.getCurrentUserTeams(userId, tenantId));
    }

}
