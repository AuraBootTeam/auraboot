package com.auraboot.framework.rbac.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
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
 * Phase 2: all subject references use memberId (tenant_member.id).
 */
@Slf4j
@RestController
@RequestMapping("/api/user-roles")
public class UserRoleController {

    @Autowired
    private UserRoleService userRoleService;

    @GetMapping
    @RequirePermission(MetaPermission.USER_ROLE_READ)
    public ApiResponse<Page<UserRole>> getUserRoles(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) String status) {

        Long tenantId = MetaContext.getCurrentTenantId();
        Page<UserRole> userRoles = userRoleService.findUserRoles(
            pageNum, pageSize, memberId, roleId, tenantId, null);
        return ApiResponse.success(userRoles);
    }

    @GetMapping("/member/{memberId}")
    @RequirePermission(MetaPermission.USER_ROLE_READ)
    public ApiResponse<List<Long>> getMemberRoleIds(@PathVariable Long memberId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> roleIds = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
        return ApiResponse.success(roleIds);
    }

    @GetMapping("/role/{roleId}")
    @RequirePermission(MetaPermission.ROLE_READ)
    public ApiResponse<List<UserRole>> getRoleMembers(@PathVariable Long roleId) {
        List<UserRole> userRoles = userRoleService.findByRoleIds(List.of(roleId));
        return ApiResponse.success(userRoles);
    }

    @PostMapping("/assign")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> assignRolesToMember(
            @RequestParam Long memberId,
            @RequestBody List<Long> roleIds,
            @CurrentUserId Long operatorId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.assignRolesToMember(memberId, roleIds, tenantId, operatorId);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/remove")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> removeRolesFromMember(
            @RequestParam Long memberId,
            @RequestBody List<Long> roleIds) {

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.removeRolesFromMember(memberId, roleIds, tenantId);
        return ApiResponse.success(result);
    }

    @PutMapping("/sync")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> syncMemberRoles(
            @RequestParam Long memberId,
            @RequestBody List<Long> roleIds,
            @CurrentUserId Long operatorId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.syncMemberRoles(memberId, roleIds, tenantId, operatorId);
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
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long roleId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> statistics = userRoleService.validateMemberRoles(memberId, tenantId);
        return ApiResponse.success(statistics);
    }

    @PostMapping("/batch-assign")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
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

    @DeleteMapping("/batch-remove")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> batchRemoveUserRoles(@RequestBody List<Long> userRoleIds) {
        int result = userRoleService.batchRemoveRoles(userRoleIds);
        return ApiResponse.success(result > 0);
    }
}
