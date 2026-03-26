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
 * 用户角色管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/user-roles")
public class UserRoleController {

    @Autowired
    private UserRoleService userRoleService;

    /**
     * 分页查询用户角色关联列表
     */
    @GetMapping
    @RequirePermission(MetaPermission.USER_ROLE_READ)
    public ApiResponse<Page<UserRole>> getUserRoles(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) String status) {
        
        Long tenantId = MetaContext.getCurrentTenantId();
        Page<UserRole> userRoles = userRoleService.findUserRoles(
            pageNum, pageSize, userId, roleId, tenantId, null);
        return ApiResponse.success(userRoles);
    }

    /**
     * 获取用户的角色列表
     */
    @GetMapping("/user/{userId}")
    @RequirePermission(MetaPermission.USER_ROLE_READ)
    public ApiResponse<List<Long>> getUserRoleIds(@PathVariable Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Long> roleIds = userRoleService.getRoleIdsByUserIdAndTenantId(userId, tenantId);
        return ApiResponse.success(roleIds);
    }

    /**
     * 获取角色的用户列表
     */
    @GetMapping("/role/{roleId}")
    @RequirePermission(MetaPermission.ROLE_READ)
    public ApiResponse<List<UserRole>> getRoleUsers(@PathVariable Long roleId) {
        List<UserRole> userRoles = userRoleService.findByRoleIds(List.of(roleId));
        return ApiResponse.success(userRoles);
    }

    /**
     * 为用户分配角色
     */
    @PostMapping("/assign")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> assignRolesToUser(
            @RequestParam Long userId,
            @RequestBody List<Long> roleIds,
            @CurrentUserId Long operatorId) {
        
        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.assignRolesToUser(userId, roleIds, tenantId, operatorId);
        return ApiResponse.success(result);
    }

    /**
     * 移除用户的角色
     */
    @DeleteMapping("/remove")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> removeRolesFromUser(
            @RequestParam Long userId,
            @RequestBody List<Long> roleIds) {
        
        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.removeRolesFromUser(userId, roleIds, tenantId);
        return ApiResponse.success(result);
    }

    /**
     * 同步用户角色
     */
    @PutMapping("/sync")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> syncUserRoles(
            @RequestParam Long userId,
            @RequestBody List<Long> roleIds,
            @CurrentUserId Long operatorId) {
        
        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = userRoleService.syncUserRoles(userId, roleIds, tenantId, operatorId);
        return ApiResponse.success(result);
    }

    /**
     * 启用用户角色
     */
    @PutMapping("/{pid}/enable")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> enableUserRole(@PathVariable String pid) {
        UserRole userRole = userRoleService.findByPid(pid);
        if (userRole == null) {
            throw new RootUnCheckedException(SystemError,"no user role found for pid "+pid);
        }
        boolean result = userRoleService.activateUserRole(userRole.getId());
        return ApiResponse.success(result);
    }

    /**
     * 禁用用户角色
     */
    @PutMapping("/{pid}/disable")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> disableUserRole(@PathVariable String pid) {
        UserRole userRole = userRoleService.findByPid(pid);
        if (userRole == null) {
            throw new RootUnCheckedException(BadParam,"Not found by the param :"+pid);
        }
        boolean result = userRoleService.deactivateUserRole(userRole.getId());
        return ApiResponse.success(result);
    }

    /**
     * 获取用户角色统计信息
     */
    @GetMapping("/statistics")
    @RequirePermission(MetaPermission.USER_ROLE_READ)
    public ApiResponse<Map<String, Object>> getUserRoleStatistics(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long roleId) {
        
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> statistics = userRoleService.validateUserRoles(userId, tenantId);
        return ApiResponse.success(statistics);
    }

    /**
     * 批量分配角色给用户
     */
    @PostMapping("/batch-assign")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> batchAssignRoles(
            @RequestBody List<UserRole> userRoles,
            @CurrentUserId Long operatorId) {
        
        Long tenantId = MetaContext.getCurrentTenantId();
        // 设置租户ID和操作人
        userRoles.forEach(userRole -> {
            userRole.setTenantId(tenantId);
            userRole.setCreatedBy(operatorId);
            userRole.setUpdatedBy(operatorId);
        });
        
        int result = userRoleService.batchAssignRoles(userRoles);
        return ApiResponse.success(result > 0);
    }

    /**
     * 批量移除用户角色
     */
    @DeleteMapping("/batch-remove")
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    public ApiResponse<Boolean> batchRemoveUserRoles(@RequestBody List<Long> userRoleIds) {
        int result = userRoleService.batchRemoveRoles(userRoleIds);
        return ApiResponse.success(result > 0);
    }
}