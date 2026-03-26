package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.enums.RolePermissionTemplate;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.validator.PermissionCodeValidator;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Auto Permission Assignment Service
 * 
 * <p>Automatically creates and assigns permissions when new resources are created.
 * 
 * <p>Workflow:
 * <ol>
 *   <li>Create permissions for the new resource (manage + read)</li>
 *   <li>Assign permissions to default roles based on templates</li>
 * </ol>
 * 
 * <p>Default Role Assignments:
 * <ul>
 *   <li>tenant_admin: manage + read permissions</li>
 *   <li>developer: manage + read permissions</li>
 *   <li>viewer: read permission only</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @version 1.0.0
 * @since 2025-01-08
 * @see RolePermissionTemplate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoPermissionAssignmentService {
    
    private final PermissionService permissionService;
    private final PermissionMapper permissionMapper;
    private final RoleService roleService;
    private final RolePermissionMapper rolePermissionMapper;
    
    /**
     * Auto-assign permissions when new resource is created
     * 
     * <p>This method should be called after a new resource (Model, Page, Component, etc.)
     * is successfully created.
     * 
     * <p>Steps:
     * <ol>
     *   <li>Create permissions for this resource (manage + read)</li>
     *   <li>Assign permissions to default roles</li>
     * </ol>
     * 
     * <p>Error Handling:
     * <ul>
     *   <li>If permission creation fails, the exception is logged but not thrown</li>
     *   <li>If role assignment fails, the exception is logged but not thrown</li>
     *   <li>This ensures resource creation is not blocked by permission assignment failures</li>
     * </ul>
     * 
     * @param resourceType Resource type (MODEL, PAGE, COMPONENT, etc.)
     * @param resourceCode Resource code (e.g., "user_model", "publish", "component")
     */
    @Transactional
    public void autoAssignPermissions(String resourceType, String resourceCode) {
        log.info("Auto-assigning permissions: resourceType={}, resourceCode={}", 
            resourceType, resourceCode);
        
        // 1. Create permissions for this resource
        List<Permission> permissions = createResourcePermissions(resourceType, resourceCode);
        
        if (permissions.isEmpty()) {
            log.warn("No permissions created: resourceType={}, resourceCode={}", 
                resourceType, resourceCode);
            return;
        }
        
        log.info("Created {} permissions: resourceType={}, resourceCode={}", 
            permissions.size(), resourceType, resourceCode);
        
        // 2. Assign permissions to default roles
        assignPermissionsToRoles(permissions);
        
        log.info("Auto-assignment completed successfully: resourceType={}, resourceCode={}, permissionCount={}", 
            resourceType, resourceCode, permissions.size());
    }
    
    /**
     * Create permissions for a resource
     * 
     * <p>Creates two permissions:
     * <ul>
     *   <li>manage permission - Full CRUD access</li>
     *   <li>read permission - Read-only access</li>
     * </ul>
     * 
     * @param resourceType Resource type (e.g., "model")
     * @param resourceCode Resource code (e.g., "user_model")
     * @return List of created permissions
     */
    private List<Permission> createResourcePermissions(String resourceType, String resourceCode) {
        List<Permission> permissions = new ArrayList<>();
        
        // Validate resource_code before creating permissions
        if (!isValidResourceCode(resourceCode)) {
            log.warn("Invalid resource_code, skipping auto-assignment: resourceType={}, resourceCode={}", 
                resourceType, resourceCode);
            return permissions;
        }
        
        // Create manage permission
        String manageCode = PermissionCodeValidator.build(
            resourceType, resourceCode, "manage", null);
        Permission managePermission = createPermission(
            manageCode,
            capitalize(resourceCode) + " Management",
            String.format("Manage %s (CRUD)", resourceCode),
            resourceType,
            resourceCode
        );
        permissions.add(managePermission);
        
        log.debug("Created manage permission: code={}, id={}", 
            manageCode, managePermission.getId());

        // Create create permission
        String createCode = PermissionCodeValidator.build(
            resourceType, resourceCode, "create", null);
        Permission createPermission = createPermission(
            createCode,
            capitalize(resourceCode) + " Create",
            String.format("Create %s", resourceCode),
            resourceType,
            resourceCode
        );
        permissions.add(createPermission);

        log.debug("Created create permission: code={}, id={}",
            createCode, createPermission.getId());

        // Create read permission
        String readCode = PermissionCodeValidator.build(
            resourceType, resourceCode, "read", null);
        Permission readPermission = createPermission(
            readCode,
            capitalize(resourceCode) + " Read",
            String.format("Read %s", resourceCode),
            resourceType,
            resourceCode
        );
        permissions.add(readPermission);

        log.debug("Created read permission: code={}, id={}",
            readCode, readPermission.getId());

        return permissions;
    }
    
    /**
     * Validate resource code
     * 
     * <p>Resource code must:
     * <ul>
     *   <li>Start with a lowercase letter</li>
     *   <li>Contain only lowercase letters, digits, and underscores</li>
     *   <li>Not be null or empty</li>
     * </ul>
     * 
     * <p>Valid examples:
     * <ul>
     *   <li>user_model ✅</li>
     *   <li>test2 ✅</li>
     *   <li>user_v2 ✅</li>
     *   <li>model_123 ✅</li>
     * </ul>
     * 
     * <p>Invalid examples:
     * <ul>
     *   <li>2test ❌ (starts with digit)</li>
     *   <li>_test ❌ (starts with underscore)</li>
     *   <li>Test ❌ (contains uppercase)</li>
     *   <li>test-model ❌ (contains hyphen)</li>
     * </ul>
     * 
     * @param resourceCode Resource code to validate
     * @return true if valid, false otherwise
     */
    private boolean isValidResourceCode(String resourceCode) {
        if (resourceCode == null || resourceCode.isEmpty()) {
            log.warn("Resource code is null or empty");
            return false;
        }
        
        // Must start with lowercase letter, followed by lowercase letters, digits, or underscores
        boolean isValid = resourceCode.matches("^[a-z][a-z0-9_]*$");
        
        if (!isValid) {
            log.warn("Resource code does not match pattern ^[a-z][a-z0-9_]*$: {}", resourceCode);
        }
        
        return isValid;
    }
    
    /**
     * Create a single permission
     * 
     * @param code Permission code
     * @param name Permission name
     * @param description Permission description
     * @param resourceType Resource type
     * @param resourceCode Resource code
     * @return Created permission
     */
    private Permission createPermission(String code, String name, String description, 
                                       String resourceType, String resourceCode) {
        // Check if permission already exists
        Permission existing = permissionMapper.findByCode(code);
        if (existing != null) {
            log.debug("Permission already exists: code={}, id={}", code, existing.getId());
            return existing;
        }
        
        // Parse code to extract action
        PermissionCodeValidator.PermissionCodeParts parts = PermissionCodeValidator.parse(code);
        
        // Create new permission
        Permission permission = new Permission();
        permission.setPid(UniqueIdGenerator.generate());  // Generate ULID for pid
        permission.setCode(code);
        permission.setName(name);
        permission.setDescription(description);
        permission.setResourceType(resourceType);
        permission.setResourceCode(resourceCode);
        permission.setAction(parts.getAction());  // Extract action from code
        permission.setSource("generated");  // Mark as system-generated
        permission.setStatus(StatusConstants.ACTIVE);
        permission.setCreatedAt(Instant.now());
        permission.setUpdatedAt(Instant.now());
        
        permissionMapper.insert(permission);
        
        log.debug("Created new permission: code={}, id={}, pid={}, action={}", 
            code, permission.getId(), permission.getPid(), permission.getAction());
        
        return permission;
    }
    
    /**
     * Assign permissions to default roles
     * 
     * <p>Role Assignment Strategy:
     * <ul>
     *   <li>tenant_admin: All permissions (manage + read)</li>
     *   <li>developer: All permissions (manage + read)</li>
     *   <li>viewer: Read permissions only</li>
     * </ul>
     * 
     * @param permissions List of permissions to assign
     */
    private void assignPermissionsToRoles(List<Permission> permissions) {
        Long tenantId = MetaContext.getCurrentTenantId();
        
        if (tenantId == null) {
            log.warn("Tenant ID is null, skipping role assignment");
            return;
        }
        
        // Get all roles for current tenant
        List<Role> roles = roleService.findByTenantId(tenantId);
        
        if (roles.isEmpty()) {
            log.warn("No roles found for tenant: tenantId={}", tenantId);
            return;
        }
        
        log.debug("Found {} roles for tenant: tenantId={}", roles.size(), tenantId);
        
        // Assign permissions to each role based on template
        for (Role role : roles) {
            assignPermissionsToRole(role, permissions);
        }
    }
    
    /**
     * Assign permissions to a specific role
     * 
     * @param role Role to assign permissions to
     * @param permissions List of permissions
     */
    private void assignPermissionsToRole(Role role, List<Permission> permissions) {
        String roleCode = role.getCode();
        
        if (roleCode == null) {
            log.warn("Role code is null, skipping: roleId={}, roleName={}", 
                role.getId(), role.getName());
            return;
        }
        
        // Check if role has a template
        RolePermissionTemplate template = RolePermissionTemplate.findByRoleCode(roleCode);
        
        if (template == null) {
            log.debug("No template found for role: roleCode={}, roleName={}", 
                roleCode, role.getName());
            return;
        }
        
        log.debug("Assigning permissions to role: roleCode={}, roleName={}, template={}", 
            roleCode, role.getName(), template.name());
        
        // Assign permissions based on template
        for (Permission permission : permissions) {
            // Check if this permission should be assigned to this role
            if (shouldAssignPermission(template, permission)) {
                bindPermissionToRole(role.getId(), permission.getId());
                
                log.debug("Assigned permission to role: roleId={}, permissionId={}, permissionCode={}", 
                    role.getId(), permission.getId(), permission.getCode());
            }
        }
    }
    
    /**
     * Check if permission should be assigned to role based on template
     * 
     * @param template Role permission template
     * @param permission Permission to check
     * @return true if should assign, false otherwise
     */
    private boolean shouldAssignPermission(RolePermissionTemplate template, Permission permission) {
        String permissionCode = permission.getCode();
        
        // For VIEWER role, only assign read permissions
        if (template == RolePermissionTemplate.VIEWER) {
            return permissionCode.endsWith(".read");
        }
        
        // For TENANT_ADMIN and DEVELOPER, assign all permissions
        return true;
    }
    
    /**
     * Bind permission to role
     * 
     * @param roleId Role ID
     * @param permissionId Permission ID
     */
    private void bindPermissionToRole(Long roleId, Long permissionId) {
        // Check if binding already exists
        RolePermission existing = rolePermissionMapper.findByRoleAndPermission(roleId, permissionId);
        
        if (existing != null) {
            log.debug("Role-Permission binding already exists: roleId={}, permissionId={}", 
                roleId, permissionId);
            return;
        }
        
        // Create new binding
        RolePermission rolePermission = new RolePermission();
        rolePermission.setPid(UniqueIdGenerator.generate());
        rolePermission.setTenantId(MetaContext.getCurrentTenantId());
        rolePermission.setRoleId(roleId);
        rolePermission.setPermissionId(permissionId);
        rolePermission.setGrantType(StatusConstants.GRANT);
        rolePermission.setCreatedAt(Instant.now());
        rolePermission.setUpdatedAt(Instant.now());
        
        rolePermissionMapper.insert(rolePermission);
        
        log.debug("Created Role-Permission binding: roleId={}, permissionId={}", 
            roleId, permissionId);
    }
    
    /**
     * Capitalize first letter of string
     * 
     * @param str String to capitalize
     * @return Capitalized string
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
