package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.dto.*;
import java.util.List;

/**
 * Permission Service Interface (V4)
 *
 * Provides business logic for Permission management.
 *
 * Key Features:
 * - Soft delete with lifecycle states
 * - Tenant isolation
 *
 * @author AuraBoot Platform
 * @version 4.0.0
 * @since 2025-01-07
 */
public interface PermissionService {
    
    /**
     * Create permission (through Git-first workflow)
     * 
     * @param request Create request
     * @return Created permission DTO
     */
    PermissionDTO create(PermissionCreateRequest request);
    
    /**
     * Update permission (through Git-first workflow)
     * 
     * @param id Permission ID
     * @param request Update request
     * @return Updated permission DTO
     */
    PermissionDTO update(Long id, PermissionUpdateRequest request);
    
    /**
     * Soft delete permission
     * 
     * @param id Permission ID
     */
    void delete(Long id);
    
    /**
     * Find permission by ID
     * 
     * @param id Permission ID
     * @return Permission DTO or null
     */
    PermissionDTO findById(Long id);
    
    /**
     * Find permission by code
     * 
     * @param code Permission code
     * @return Permission DTO or null
     */
    PermissionDTO findByCode(String code);
    
    /**
     * Find permissions by resource type
     * 
     * @param resourceType Resource type
     * @return List of permissions
     */
    List<PermissionDTO> findByResourceType(String resourceType);
    
    /**
     * Find permissions by resource
     * 
     * @param resourceType Resource type
     * @param resourceCode Resource code
     * @return List of permissions
     */
    List<PermissionDTO> findByResource(String resourceType, String resourceCode);
    
    /**
     * Find user permissions (through RBAC)
     * 
     * @param userId User ID
     * @return List of permissions
     */
    List<PermissionDTO> findUserPermissions(Long userId);
    
    /**
     * Deprecate permission (lifecycle management)
     * 
     * @param id Permission ID
     */
    void deprecate(Long id);
    
    /**
     * Archive permission (lifecycle management)
     * 
     * @param id Permission ID
     */
    void archive(Long id);
    
    /**
     * Find deprecated permissions for archiving
     * 
     * @param monthsThreshold Months threshold (e.g., 6)
     * @return List of permissions to be archived
     */
    List<PermissionDTO> findDeprecatedForArchive(int monthsThreshold);
    
    /**
     * Find permissions by role
     * 
     * <p>Returns all permissions bound to a specific role through RolePermission bindings.
     * 
     * @param roleId Role ID
     * @return List of permissions
     */
    List<PermissionDTO> findRolePermissions(Long roleId);
    
    /**
     * Bind permission to role
     * 
     * <p>Creates a GRANT binding between a role and a permission.
     * 
     * @param roleId Role ID
     * @param permissionId Permission ID
     */
    void bindToRole(Long roleId, Long permissionId);
    
    /**
     * Unbind permission from role
     * 
     * <p>Removes the binding between a role and a permission.
     * 
     * @param roleId Role ID
     * @param permissionId Permission ID
     */
    void unbindFromRole(Long roleId, Long permissionId);
    
    /**
     * Find permission references
     * 
     * <p>Returns all roles that reference this permission.
     * 
     * @param permissionId Permission ID
     * @return List of permission references
     */
    List<PermissionReferenceDTO> findReferences(Long permissionId);

    /**
     * Find all active permissions
     *
     * @return List of active permissions
     */
    List<PermissionDTO> findAllActive();
}
