package com.auraboot.framework.permission.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Role-Permission Service Interface
 * 
 * Manages the association between Roles and permissions.
 * 
 * <p>Key Features:
 * <ul>
 *   <li>Role-Permission binding management</li>
 *   <li>Batch operations support</li>
 *   <li>Cache eviction on changes</li>
 *   <li>Statistics and reporting</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @since V4
 */
public interface RolePermissionService {
    
    /**
     * Assign permissions to role
     * 
     * <p>This method creates Role-Permission bindings.
     * 
     * <p>Side Effects:
     * <ul>
     *   <li>Evicts UserPermissionService cache for all users with this role</li>
     * </ul>
     * 
     * @param roleId Role ID
     * @param permissionIds List of permission IDs to assign
     * @return true if successful
     */
    boolean assignPermissionsToRole(Long roleId, List<Long> permissionIds);
    
    /**
     * Remove a permission from role
     * 
     * <p>Side Effects:
     * <ul>
     *   <li>Evicts UserPermissionService cache for all users with this role</li>
     * </ul>
     * 
     * @param roleId Role ID
     * @param permissionId Permission ID to remove
     * @return true if successful
     */
    boolean removePermission(Long roleId, Long permissionId);
    
    /**
     * Remove all permissions from role
     * 
     * <p>Used when deleting a role.
     * 
     * <p>Side Effects:
     * <ul>
     *   <li>Evicts UserPermissionService cache for all users with this role</li>
     * </ul>
     * 
     * @param roleId Role ID
     * @return true if successful
     */
    boolean removeAllPermissionsByRoleId(Long roleId);
    
    /**
     * Get permission IDs assigned to role
     * 
     * @param roleId Role ID
     * @return Set of permission IDs
     */
    Set<Long> getPermissionIdsByRoleId(Long roleId);
    
    /**
     * Get permission PIDs assigned to role
     * 
     * @param roleId Role ID
     * @return List of permission PIDs
     */
    List<String> getPermissionPidsByRoleId(Long roleId);
    
    /**
     * Sync role permissions by PIDs
     * 
     * <p>This method replaces all existing Role-Permission bindings
     * with the provided permission PIDs.
     * 
     * <p>Implementation:
     * <ol>
     *   <li>Remove all existing bindings</li>
     *   <li>Query permission IDs by PIDs</li>
     *   <li>Create new bindings</li>
     *   <li>Evict user permission cache</li>
     * </ol>
     * 
     * @param roleId Role ID
     * @param permissionPids List of permission PIDs
     * @param grantType Grant type (e.g., "grant", "inherit")
     * @return true if successful
     */
    boolean syncRolePermissionsByPids(Long roleId, List<String> permissionPids, String grantType);
    
    /**
     * Remove permissions from role by PIDs
     * 
     * @param roleId Role ID
     * @param permissionPids List of permission PIDs to remove
     * @return true if successful
     */
    boolean removePermissionsFromRoleByPids(Long roleId, List<String> permissionPids);
    
    /**
     * Get role permission statistics
     * 
     * @param roleId Role ID
     * @return Statistics map containing:
     *         - totalPermissions: total number of permissions
     *         - byCategory: permissions grouped by category
     *         - byResource: permissions grouped by resource type
     */
    Map<String, Object> getRolePermissionStatistics(Long roleId);
    
    /**
     * Copy permissions from source role to target role
     * 
     * <p>Used when creating a new role based on an existing role.
     * 
     * @param sourceRoleId Source role ID
     * @param targetRoleId Target role ID
     * @return true if successful
     */
    boolean copyRolePermissions(Long sourceRoleId, Long targetRoleId);
}
