package com.auraboot.framework.rbac.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.rbac.dto.AssignRolesByCodeRequest;
import com.auraboot.framework.rbac.dto.AssignRolesByPidRequest;
import com.auraboot.framework.rbac.dto.UserRoleResponse;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.auraboot.framework.common.constant.ResponseCode.*;

/**
 * User-role management controller.
 * Public read contracts return PIDs only. PID/code mutation paths are the
 * supported contract; ID-based mutation paths are kept temporarily for
 * compatibility and marked deprecated for removal after the migration window.
 */
@Slf4j
@RestController
@RequestMapping("/api/user-roles")
public class UserRoleController {

    @Autowired
    private UserRoleService userRoleService;

    @GetMapping
    @RequirePermission(MetaPermission.USER_ROLE_READ)
    public ApiResponse<Page<UserRoleResponse>> getUserRoles(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String memberPid,
            @RequestParam(required = false) String rolePid,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) String status) {

        Long tenantId = MetaContext.getCurrentTenantId();
        Page<UserRoleResponse> userRoles = userRoleService.findUserRoleResponses(
            pageNum, pageSize, memberPid, rolePid, memberId, roleId, tenantId, null);
        return ApiResponse.success(userRoles);
    }

    @GetMapping("/member/{memberPid}")
    @RequirePermission(MetaPermission.USER_ROLE_READ)
    public ApiResponse<List<String>> getMemberRolePids(@PathVariable String memberPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<String> rolePids = userRoleService.getRolePidsByMemberPidAndTenantId(memberPid, tenantId);
        return ApiResponse.success(rolePids);
    }

    @GetMapping("/role/{rolePid}")
    @RequirePermission(MetaPermission.ROLE_READ)
    public ApiResponse<List<UserRoleResponse>> getRoleMembers(@PathVariable String rolePid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<UserRoleResponse> userRoles = userRoleService.findRoleMemberResponsesByRolePid(rolePid, tenantId);
        return ApiResponse.success(userRoles);
    }

    @PostMapping("/assign")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    @Deprecated
    public ApiResponse<Boolean> assignRolesToMember(
            @RequestParam Long memberId,
            @RequestBody List<Long> roleIds,
            @CurrentUserId Long operatorId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.assignRolesToMember(memberId, roleIds, tenantId, operatorId);
        return ApiResponse.success(result);
    }

    @PostMapping("/assign-by-code")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> assignRolesToMemberByCode(
            @RequestBody AssignRolesByCodeRequest request,
            @CurrentUserId Long operatorId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.assignRolesToMemberByRoleCodes(
                request.getMemberPid(), request.getRoleCodes(), tenantId, operatorId);
        return ApiResponse.success(result);
    }

    @PostMapping("/assign-by-pid")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> assignRolesToMemberByPid(
            @RequestBody AssignRolesByPidRequest request,
            @CurrentUserId Long operatorId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.assignRolesToMemberByRolePids(
                request.getMemberPid(), request.getRolePids(), tenantId, operatorId);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/remove")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    @Deprecated
    public ApiResponse<Boolean> removeRolesFromMember(
            @RequestParam Long memberId,
            @RequestBody List<Long> roleIds) {

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.removeRolesFromMember(memberId, roleIds, tenantId);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/remove-by-pid")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> removeRolesFromMemberByPid(
            @RequestBody AssignRolesByPidRequest request) {

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.removeRolesFromMemberByRolePids(
                request.getMemberPid(), request.getRolePids(), tenantId);
        return ApiResponse.success(result);
    }

    @PutMapping("/sync")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    @Deprecated
    public ApiResponse<Boolean> syncMemberRoles(
            @RequestParam Long memberId,
            @RequestBody List<Long> roleIds,
            @CurrentUserId Long operatorId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.syncMemberRoles(memberId, roleIds, tenantId, operatorId);
        return ApiResponse.success(result);
    }

    @PutMapping("/sync-by-pid")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> syncMemberRolesByPid(
            @RequestBody AssignRolesByPidRequest request,
            @CurrentUserId Long operatorId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.syncMemberRolesByRolePids(
                request.getMemberPid(), request.getRolePids(), tenantId, operatorId);
        return ApiResponse.success(result);
    }

    @PutMapping("/{pid}/enable")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> enableUserRole(@PathVariable String pid) {
        UserRole userRole = userRoleService.findByPid(pid);
        if (userRole == null) {
            throw new RootUnCheckedException(SystemError, "no user role found for pid " + pid);
        }
        boolean result = userRoleService.activateUserRole(userRole.getId());
        return ApiResponse.success(result);
    }

    @PutMapping("/{pid}/disable")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> disableUserRole(@PathVariable String pid) {
        UserRole userRole = userRoleService.findByPid(pid);
        if (userRole == null) {
            throw new RootUnCheckedException(BadParam, "Not found by the param :" + pid);
        }
        boolean result = userRoleService.deactivateUserRole(userRole.getId());
        return ApiResponse.success(result);
    }

    @GetMapping("/statistics")
    @RequirePermission(MetaPermission.USER_ROLE_READ)
    public ApiResponse<Map<String, Object>> getMemberRoleStatistics(
            @RequestParam(required = false) String memberPid,
            @RequestParam(required = false) String rolePid,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long roleId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> statistics = memberPid != null
                ? userRoleService.validateMemberRolesByPid(memberPid, tenantId)
                : userRoleService.validateMemberRoles(memberId, tenantId);
        return ApiResponse.success(statistics);
    }

    @PostMapping("/batch-assign")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    @Deprecated
    public ApiResponse<Boolean> batchAssignRoles(
            @RequestBody List<UserRole> userRoles,
            @CurrentUserId Long operatorId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        userRoles.forEach(userRole -> {
            userRole.setTenantId(tenantId);
            userRole.setCreatedBy(operatorId);
            userRole.setUpdatedBy(operatorId);
        });

        int result = userRoleService.batchAssignRoles(userRoles);
        return ApiResponse.success(result > 0);
    }

    @PostMapping("/batch-assign-by-pid")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> batchAssignRolesByPid(
            @RequestBody List<AssignRolesByPidRequest> requests,
            @CurrentUserId Long operatorId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = requests.stream()
                .allMatch(request -> userRoleService.assignRolesToMemberByRolePids(
                        request.getMemberPid(), request.getRolePids(), tenantId, operatorId));
        return ApiResponse.success(result);
    }

    @DeleteMapping("/batch-remove")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    @Deprecated
    public ApiResponse<Boolean> batchRemoveUserRoles(@RequestBody List<Long> userRoleIds) {
        int result = userRoleService.batchRemoveRoles(userRoleIds);
        return ApiResponse.success(result > 0);
    }

    @DeleteMapping("/batch-remove-by-pid")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> batchRemoveUserRolesByPid(@RequestBody List<String> userRolePids) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int result = userRoleService.batchRemoveRolesByPids(userRolePids, tenantId);
        return ApiResponse.success(result > 0);
    }
}
