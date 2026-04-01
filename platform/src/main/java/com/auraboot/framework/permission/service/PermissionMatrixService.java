package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.dto.PermissionGrantRequest;
import com.auraboot.framework.permission.dto.PermissionMatrixDTO;

import java.util.List;

/**
 * Permission Matrix Service
 *
 * <p>Transforms the flat permission tree into a structured
 * Module -> Resource -> Action matrix for the permission management UI.
 */
public interface PermissionMatrixService {

    /**
     * Get the full permission matrix (all permissions, none granted).
     *
     * @param tenantId Tenant ID
     * @return Permission matrix
     */
    PermissionMatrixDTO getMatrix(Long tenantId);

    /**
     * Get the permission matrix for a specific role, with granted flags set.
     *
     * @param tenantId Tenant ID
     * @param roleId   Role ID
     * @return Permission matrix with granted status
     */
    PermissionMatrixDTO getMatrixForRole(Long tenantId, Long roleId);

    /**
     * Batch update role permissions based on matrix checkbox changes.
     *
     * @param roleId Role ID
     * @param grants List of permission grant/revoke requests
     */
    void batchUpdateRolePermissions(Long roleId, List<PermissionGrantRequest> grants);
}
