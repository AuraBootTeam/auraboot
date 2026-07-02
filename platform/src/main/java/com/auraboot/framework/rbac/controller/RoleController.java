package com.auraboot.framework.rbac.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.rbac.dto.CopyPermissionsRequest;
import com.auraboot.framework.rbac.dto.RoleResponse;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.auraboot.framework.rbac.service.RoleService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

/**
 * 角色管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/roles")
@Tag(name = "Roles", description = "Role and permission management")
public class RoleController {

    @Autowired
    private RoleService roleService;

    @Autowired
    private RolePermissionService rolePermissionService;

    @Autowired
    private PluginResourceTracker pluginResourceTracker;

    /**
     * 分页查询角色列表
     */
    @GetMapping
    @RequirePermission(MetaPermission.ROLE_READ)
    public ApiResponse<Page<RoleResponse>> getRoles(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        
        Long tenantId = MetaContext.getCurrentTenantId();
        Page<Role> roles = roleService.findRoles(
            pageNum, pageSize, tenantId, keyword, type, status);
        return ApiResponse.success(toResponsePage(roles));
    }

    /**
     * 获取当前租户的所有角色
     */
    @GetMapping("/all")
    @RequirePermission(MetaPermission.ROLE_READ)
    public ApiResponse<List<RoleResponse>> getAllRoles() {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Role> roles = roleService.findByTenantId(tenantId);
        return ApiResponse.success(roles.stream().map(RoleResponse::from).toList());
    }

    /**
     * 根据PID获取角色详情
     */
    /**
     * Resolve a role by its (non-enumerable) pid, scoped to the current tenant.
     * {@code ab_role} is excluded from the tenant line interceptor, so resolving a
     * role by a global pid must re-assert tenant ownership here. Returns null for a
     * role owned by a different tenant (treated as not-found → 404) so a tenant admin
     * cannot read/mutate another tenant's role. System roles (tenantId == null)
     * remain accessible.
     */
    private Role resolveRoleForCurrentTenant(String pid) {
        Role role = roleService.findByPid(pid);
        if (role == null) {
            return null;
        }
        Long currentTenant = MetaContext.getCurrentTenantId();
        if (role.getTenantId() != null && !role.getTenantId().equals(currentTenant)) {
            return null; // cross-tenant access → not found
        }
        return role;
    }

    @GetMapping("/{pid}")
    @RequirePermission(MetaPermission.ROLE_READ)
    public ApiResponse<RoleResponse> getRole(@PathVariable String pid) {
        Role role = resolveRoleForCurrentTenant(pid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam,"Not found by the param :"+pid);
        }
        return ApiResponse.success(RoleResponse.from(role));
    }

    /**
     * 创建角色
     */
    @PostMapping
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<RoleResponse> createRole(
            @RequestBody Role role,
            @CurrentUserId Long userId) {
        
        Long tenantId = MetaContext.getCurrentTenantId();
        role.setTenantId(tenantId);
        role.setCreatedBy(userId);
        role.setUpdatedBy(userId);
        Role created = roleService.createRole(role);
        return ApiResponse.success(RoleResponse.from(created));
    }

    /**
     * 更新角色
     */
    @PutMapping("/{pid}")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<RoleResponse> updateRole(
            @PathVariable String pid,
            @RequestBody Role role,
            @CurrentUserId Long userId) {
        
        Role existingRole = resolveRoleForCurrentTenant(pid);
        if (existingRole == null) {
            throw new RootUnCheckedException(BadParam,"Not found by the param :"+pid);
        }
        role.setId(existingRole.getId());
        role.setUpdatedBy(userId);
        Role updated = roleService.updateRole(role);
        pluginResourceTracker.markAsUserModified(ResourceType.ROLE, existingRole.getCode());
        return ApiResponse.success(RoleResponse.from(updated));
    }

    /**
     * 删除角色
     */
    @DeleteMapping("/{pid}")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<Boolean> deleteRole(@PathVariable String pid) {
        Role role = resolveRoleForCurrentTenant(pid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam,"Not found by the param :"+pid);
        }
        pluginResourceTracker.markAsUserModified(ResourceType.ROLE, role.getCode());
        boolean result = roleService.deleteRole(role.getId());
        return ApiResponse.success(result);
    }

    /**
     * 启用角色
     */
    @PutMapping("/{pid}/enable")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<Boolean> enableRole(@PathVariable String pid) {
        Role role = resolveRoleForCurrentTenant(pid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam,"Not found by the param :"+pid);
        }
        boolean result = roleService.enableRole(role.getId());
        return ApiResponse.success(result);
    }

    /**
     * 禁用角色
     */
    @PutMapping("/{pid}/disable")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<Boolean> disableRole(@PathVariable String pid) {
        Role role = resolveRoleForCurrentTenant(pid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam,"Not found by the param :"+pid);
        }
        boolean result = roleService.disableRole(role.getId());
        return ApiResponse.success(result);
    }

    /**
     * Get role permissions
     */
    @GetMapping("/{pid}/permissions")
    @RequirePermission(MetaPermission.ROLE_READ)
    public ApiResponse<List<String>> getRolePermissions(@PathVariable String pid) {
        Role role = resolveRoleForCurrentTenant(pid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam, "Not found by the param: " + pid);
        }
        List<String> permissionPids = rolePermissionService.getPermissionPidsByRoleId(role.getId());
        return ApiResponse.success(permissionPids);
    }

    /**
     * Assign permissions to role
     */
    @PostMapping("/{pid}/permissions")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<Boolean> assignPermissions(
            @PathVariable String pid,
            @RequestBody List<String> permissionPids) {

        Role role = resolveRoleForCurrentTenant(pid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam, "Not found by the param: " + pid);
        }
        boolean result = rolePermissionService.syncRolePermissionsByPids(role.getId(), permissionPids, "grant");
        return ApiResponse.success(result);
    }

    /**
     * Remove permissions from role
     */
    @DeleteMapping("/{pid}/permissions")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<Boolean> removePermissions(
            @PathVariable String pid,
            @RequestBody List<String> permissionPids) {

        Role role = resolveRoleForCurrentTenant(pid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam, "Not found by the param: " + pid);
        }
        boolean result = rolePermissionService.removePermissionsFromRoleByPids(role.getId(), permissionPids);
        return ApiResponse.success(result);
    }

    /**
     * 获取角色统计信息
     */
    @GetMapping("/{pid}/statistics")
    @RequirePermission(MetaPermission.ROLE_READ)
    public ApiResponse<Map<String, Object>> getRoleStatistics(@PathVariable String pid) {
        Role role = resolveRoleForCurrentTenant(pid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam,"Not found by the param :"+pid);
        }
        Map<String, Object> statistics = rolePermissionService.getRolePermissionStatistics(role.getId());
        return ApiResponse.success(statistics);
    }

    /**
     * Copy role permissions
     */
    @PostMapping("/{pid}/copy-permissions")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<Boolean> copyRolePermissions(
            @PathVariable String pid,
            @RequestBody @jakarta.validation.Valid CopyPermissionsRequest request) {

        Role role = resolveRoleForCurrentTenant(pid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam, "Not found by the param: " + pid);
        }
        boolean result = rolePermissionService.copyRolePermissions(role.getId(), request.targetRoleId());
        return ApiResponse.success(result);
    }

    private Page<RoleResponse> toResponsePage(Page<Role> roles) {
        Page<RoleResponse> response = new Page<>(roles.getCurrent(), roles.getSize(), roles.getTotal());
        response.setPages(roles.getPages());
        response.setRecords(roles.getRecords().stream().map(RoleResponse::from).toList());
        return response;
    }
}
