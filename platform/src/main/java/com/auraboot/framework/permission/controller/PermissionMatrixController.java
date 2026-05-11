package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.PermissionExplanation;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.dto.DataScopeUpdateRequest;
import com.auraboot.framework.permission.dto.PermissionGrantRequest;
import com.auraboot.framework.permission.dto.PermissionMatrixDTO;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.PermissionMatrixService;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

import java.util.List;
import java.util.Map;

/**
 * Permission Matrix Controller
 *
 * <p>Provides structured permission data for the matrix UI:
 * Module -> Resource -> Action with granted/revoked status per role.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/permissions/matrix - Get full permission matrix</li>
 *   <li>GET /api/permissions/matrix/{roleId} - Get matrix with role grants</li>
 *   <li>PUT /api/permissions/matrix/{roleId}/batch - Batch update role permissions</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/permissions/matrix")
@RequiredArgsConstructor
@Validated
@RequirePermission(MetaPermission.PERMISSION_MANAGE)
@Tag(name = "Permission Matrix", description = "Permission matrix for role management")
public class PermissionMatrixController {

    private final PermissionMatrixService matrixService;
    private final RoleService roleService;
    private final DataScopeService dataScopeService;
    private final PermissionPolicyService policyService;
    private final PermissionMapper permissionMapper;
    private final PermissionEvaluator permissionEvaluator;

    /**
     * Get the full permission matrix (no role context, all granted=false).
     */
    @GetMapping
    @Operation(summary = "Get permission matrix")
    public ApiResponse<PermissionMatrixDTO> getMatrix() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(matrixService.getMatrix(tenantId));
    }

    /**
     * Get the permission matrix for a specific role, with granted flags set.
     *
     * @param rolePid Role PID (string identifier, avoids BigInt precision loss)
     */
    @GetMapping("/{rolePid}")
    @Operation(summary = "Get permission matrix for role")
    public ApiResponse<PermissionMatrixDTO> getMatrixForRole(@PathVariable String rolePid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Role role = roleService.findByPid(rolePid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam, "Role not found by PID: " + rolePid);
        }
        return ApiResponse.success(matrixService.getMatrixForRole(tenantId, role.getId()));
    }

    /**
     * Batch update role permissions from matrix checkbox changes.
     *
     * @param rolePid Role PID (string identifier, avoids BigInt precision loss)
     * @param grants List of grant/revoke requests
     */
    @PutMapping("/{rolePid}/batch")
    @Operation(summary = "Batch update role permissions")
    public ApiResponse<Void> batchUpdate(
            @PathVariable String rolePid,
            @RequestBody @Valid List<PermissionGrantRequest> grants) {
        Role role = roleService.findByPid(rolePid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam, "Role not found by PID: " + rolePid);
        }
        log.info("Batch updating permissions for role: rolePid={}, count={}", rolePid, grants.size());
        matrixService.batchUpdateRolePermissions(role.getId(), grants);
        return ApiResponse.success();
    }

    /**
     * Update data scope configuration for a specific role+resource+action combination.
     *
     * @param rolePid Role PID (string identifier, avoids BigInt precision loss)
     * @param request Scope update request containing resourceCode, actionCode, scopeType, mergeStrategy
     */
    @PutMapping("/{rolePid}/scope")
    @Operation(summary = "Update data scope for role+resource+action")
    public ApiResponse<Void> updateScope(
            @PathVariable String rolePid,
            @RequestBody DataScopeUpdateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Role role = roleService.findByPid(rolePid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam, "Role not found by PID: " + rolePid);
        }
        dataScopeService.setScope(tenantId, role.getId(),
            request.resourceCode(), request.actionCode(),
            request.scopeType(), request.mergeStrategy());
        return ApiResponse.success();
    }

    /**
     * Get policy values for a specific role+permission combination.
     *
     * @param rolePid       Role PID
     * @param permissionPid Permission PID
     */
    @GetMapping("/{rolePid}/policy/{permissionPid}")
    @Operation(summary = "Get policy values for role+permission")
    public ApiResponse<Map<String, Object>> getPolicy(
            @PathVariable String rolePid,
            @PathVariable String permissionPid) {
        Role role = roleService.findByPid(rolePid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam, "Role not found by PID: " + rolePid);
        }
        Permission permission = findPermissionByPid(permissionPid);
        Map<String, Object> policy = policyService.getPolicy(role.getId(), permission.getId());
        return ApiResponse.success(policy);
    }

    /**
     * Set policy values for a specific role+permission combination.
     *
     * @param rolePid       Role PID
     * @param permissionPid Permission PID
     * @param policyValues  Policy parameter values
     */
    @PutMapping("/{rolePid}/policy/{permissionPid}")
    @Operation(summary = "Set policy values for role+permission")
    public ApiResponse<Void> setPolicy(
            @PathVariable String rolePid,
            @PathVariable String permissionPid,
            @RequestBody Map<String, Object> policyValues) {
        Role role = roleService.findByPid(rolePid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam, "Role not found by PID: " + rolePid);
        }
        Permission permission = findPermissionByPid(permissionPid);
        log.info("Setting policy for role+permission: rolePid={}, permissionPid={}, keys={}",
                rolePid, permissionPid, policyValues.keySet());
        policyService.setPolicy(role.getId(), permission.getId(), policyValues);
        return ApiResponse.success();
    }

    /**
     * Explain WHY a permission decision was made — for audit/compliance.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier (e.g. model code)
     * @param action   action identifier (e.g. "view", "create", "edit", "delete")
     * @param recordId optional target record ID
     */
    @GetMapping("/explain")
    @Operation(summary = "Explain permission decision for audit")
    @SuppressWarnings("java/csrf-unprotected-request-type")
    public ApiResponse<PermissionExplanation> explain(
            @RequestParam Long memberId,
            @RequestParam String resource,
            @RequestParam String action,
            @RequestParam(required = false) Long recordId) {
        return ApiResponse.success(permissionEvaluator.explain(memberId, resource, action, recordId));
    }

    private Permission findPermissionByPid(String permissionPid) {
        List<Permission> permissions = permissionMapper.findByPids(List.of(permissionPid));
        if (permissions.isEmpty()) {
            throw new RootUnCheckedException(BadParam, "Permission not found by PID: " + permissionPid);
        }
        return permissions.get(0);
    }
}
