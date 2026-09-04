package com.auraboot.framework.tenant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.tenant.controller.request.ApproveRequest;
import com.auraboot.framework.tenant.controller.request.BatchMemberStatusRequest;
import com.auraboot.framework.tenant.controller.request.MemberLifecycleRequest;
import com.auraboot.framework.tenant.dto.MemberQueryRequest;
import com.auraboot.framework.tenant.dto.MemberResponse;
import com.auraboot.framework.tenant.dto.TenantMemberOffboardingImpactResponse;
import com.auraboot.framework.tenant.dto.TenantMemberOffboardingCandidate;
import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;
import com.auraboot.framework.tenant.service.TenantMemberApplicationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/tenant/members")
@Tag(name = "Tenant Members", description = "Tenant membership management")
public class TenantMemberController {

    private static final int MAX_BATCH_SIZE = 100;

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
    @RequirePermission(MetaPermission.TENANT_MEMBER_MANAGE)
    @ResponseBody
    public ApiResponse<Boolean> approveMember(
            @PathVariable String memberPid,
            @RequestBody ApproveRequest approveRequest,
            @CurrentUserId Long userId) {
        
        boolean result = memberApplicationService.approveMember(memberPid,  approveRequest.getAction(), approveRequest.getReason(), userId);
        return ApiResponse.success(result);
    }

    @PutMapping("/{memberPid}/status")
    @RequirePermission(MetaPermission.TENANT_MEMBER_MANAGE)
    @ResponseBody
    public ApiResponse<Boolean> updateMemberStatus(
            @PathVariable String memberPid,
            @RequestBody MemberLifecycleRequest approveRequest,
            @CurrentUserId Long userId) {
        
        boolean result = memberApplicationService.updateMemberStatus(
                memberPid, approveRequest.getAction(), approveRequest.getReason(),
                approveRequest.getTargetMemberPid(), userId);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{memberPid}")
    @RequirePermission(MetaPermission.TENANT_MEMBER_MANAGE)
    @ResponseBody
    public ApiResponse<Boolean> removeMember(
            @PathVariable String memberPid,
            @RequestParam(required = false) String targetMemberPid,
            @CurrentUserId Long userId) {
        
        boolean result = memberApplicationService.removeMember(memberPid, targetMemberPid, userId);
        return ApiResponse.success(result);
    }

    @GetMapping("/{memberPid}/offboarding-impact")
    @RequirePermission(MetaPermission.TENANT_MEMBER_MANAGE)
    public ApiResponse<TenantMemberOffboardingImpactResponse> inspectOffboardingImpact(
            @PathVariable String memberPid,
            @RequestParam(required = false) String targetMemberPid,
            @RequestParam(defaultValue = "remove") String action,
            @CurrentUserId Long userId) {
        return ApiResponse.success(memberApplicationService.inspectOffboardingImpact(
                memberPid, targetMemberPid, action, userId));
    }

    @GetMapping("/{memberPid}/offboarding-candidates")
    @RequirePermission(MetaPermission.TENANT_MEMBER_MANAGE)
    public ApiResponse<List<TenantMemberOffboardingCandidate>> listOffboardingCandidates(
            @PathVariable String memberPid,
            @CurrentUserId Long userId) {
        return ApiResponse.success(memberApplicationService.listOffboardingCandidates(memberPid, userId));
    }

    @GetMapping("/{memberPid}/teams")
    @ResponseBody
    public ApiResponse<List<Map<String, Object>>> getMemberTeams(@PathVariable String memberPid) {
        return ApiResponse.success(memberApplicationService.getMemberTeams(memberPid));
    }

    @PostMapping("/batch-delete")
    @RequirePermission(MetaPermission.TENANT_MEMBER_MANAGE)
    @ResponseBody
    public ApiResponse<Boolean> batchRemoveMembers(
            @RequestBody List<String> memberPids,
            @CurrentUserId Long userId) {

        boolean result = memberApplicationService.batchRemoveMembers(memberPids, userId);
        return ApiResponse.success(result);
    }

    /**
     * Batch enable/disable members. Each member transitions independently —
     * a failure on one member does not roll the others back.
     * Action values match the single-member status endpoint:
     * active | inactive | suspended.
     */
    @PostMapping("/batch-status")
    @RequirePermission(MetaPermission.TENANT_MEMBER_MANAGE)
    @ResponseBody
    public ApiResponse<Map<String, Object>> batchUpdateMemberStatus(
            @RequestBody BatchMemberStatusRequest request,
            @CurrentUserId Long userId) {

        List<String> memberPids = request.getMemberPids();
        if (memberPids == null || memberPids.isEmpty()) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "memberPids is required");
        }
        if (memberPids.size() > MAX_BATCH_SIZE) {
            throw new RootUnCheckedException(ResponseCode.BadParam,
                    "Batch size is limited to " + MAX_BATCH_SIZE + " members");
        }

        int succeeded = 0;
        List<Map<String, String>> failed = new ArrayList<>();
        for (String memberPid : memberPids) {
            try {
                memberApplicationService.updateMemberStatus(
                        memberPid, request.getAction(), request.getReason(), null, userId);
                succeeded++;
            } catch (Exception e) {
                log.warn("Batch status transition failed for member {}: {}", memberPid, e.getMessage());
                failed.add(Map.of("memberPid", memberPid,
                        "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return ApiResponse.success(Map.of("succeeded", succeeded, "failed", failed));
    }
}
