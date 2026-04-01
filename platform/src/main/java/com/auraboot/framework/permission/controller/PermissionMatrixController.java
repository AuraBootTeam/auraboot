package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.dto.PermissionGrantRequest;
import com.auraboot.framework.permission.dto.PermissionMatrixDTO;
import com.auraboot.framework.permission.service.PermissionMatrixService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
     * @param roleId Role ID
     */
    @GetMapping("/{roleId}")
    @Operation(summary = "Get permission matrix for role")
    public ApiResponse<PermissionMatrixDTO> getMatrixForRole(@PathVariable Long roleId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(matrixService.getMatrixForRole(tenantId, roleId));
    }

    /**
     * Batch update role permissions from matrix checkbox changes.
     *
     * @param roleId Role ID
     * @param grants List of grant/revoke requests
     */
    @PutMapping("/{roleId}/batch")
    @Operation(summary = "Batch update role permissions")
    public ApiResponse<Void> batchUpdate(
            @PathVariable Long roleId,
            @RequestBody @Valid List<PermissionGrantRequest> grants) {
        log.info("Batch updating permissions for role: roleId={}, count={}", roleId, grants.size());
        matrixService.batchUpdateRolePermissions(roleId, grants);
        return ApiResponse.success();
    }
}
