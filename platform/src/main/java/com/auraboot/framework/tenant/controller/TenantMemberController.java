package com.auraboot.framework.tenant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.tenant.controller.request.ApproveRequest;
import com.auraboot.framework.tenant.dto.MemberQueryRequest;
import com.auraboot.framework.tenant.dto.MemberResponse;
import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;
import com.auraboot.framework.tenant.service.TenantMemberApplicationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/tenant/members")
@Tag(name = "Tenant Members", description = "Tenant membership management")
public class TenantMemberController {

    @Autowired
    private TenantMemberApplicationService memberApplicationService;
    @Autowired
    private CurrentUserTeamResolver currentUserTeamResolver;

    @PostMapping("/search")
    @ResponseBody
    public ApiResponse<PaginationResult<MemberResponse>> searchMembers(
            @RequestBody MemberQueryRequest request,
            @CurrentUserId Long userId) {
        
        PaginationResult<MemberResponse> result = memberApplicationService.searchMembers(request, userId);
        return ApiResponse.success(result);
    }

    @GetMapping("/current/teams")
    @ResponseBody
    public ApiResponse<List<String>> getCurrentUserTeams() {
        return ApiResponse.success(currentUserTeamResolver.resolveCurrentUserTeamIds());
    }

    @GetMapping("/{memberPid}")
    @ResponseBody
    public ApiResponse<MemberResponse> getMember(
            @PathVariable String memberPid,
            @CurrentUserId Long userId) {
        
        MemberResponse response = memberApplicationService.getMemberById(memberPid, userId);
        return ApiResponse.success(response);
    }

    @PostMapping("/{memberPid}/approve")
    @ResponseBody
    public ApiResponse<Boolean> approveMember(
            @PathVariable String memberPid,
            @RequestBody ApproveRequest approveRequest,
            @CurrentUserId Long userId) {
        
        boolean result = memberApplicationService.approveMember(memberPid,  approveRequest.getAction(), approveRequest.getReason(), userId);
        return ApiResponse.success(result);
    }

    @PutMapping("/{memberPid}/status")
    @ResponseBody
    public ApiResponse<Boolean> updateMemberStatus(
            @PathVariable String memberPid,
            @RequestBody ApproveRequest approveRequest,
            @CurrentUserId Long userId) {
        
        boolean result = memberApplicationService.updateMemberStatus(memberPid, approveRequest.getAction(), approveRequest.getReason(), userId);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{memberPid}")
    @ResponseBody
    public ApiResponse<Boolean> removeMember(
            @PathVariable String memberPid,
            @CurrentUserId Long userId) {
        
        boolean result = memberApplicationService.removeMember(memberPid, userId);
        return ApiResponse.success(result);
    }

    @GetMapping("/{memberPid}/teams")
    @ResponseBody
    public ApiResponse<List<Map<String, Object>>> getMemberTeams(@PathVariable String memberPid) {
        return ApiResponse.success(memberApplicationService.getMemberTeams(memberPid));
    }

    @PostMapping("/batch-delete")
    @ResponseBody
    public ApiResponse<Boolean> batchRemoveMembers(
            @RequestBody List<String> memberPids,
            @CurrentUserId Long userId) {

        boolean result = memberApplicationService.batchRemoveMembers(memberPids, userId);
        return ApiResponse.success(result);
    }
}
