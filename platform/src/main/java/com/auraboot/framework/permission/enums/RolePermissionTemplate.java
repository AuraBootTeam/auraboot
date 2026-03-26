package com.auraboot.framework.permission.enums;

import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Role Permission Template Enum
 * 
 * <p>Defines permission templates for different role types.
 * When a new resource is created, permissions are automatically
 * generated and assigned to roles based on these templates.
 * 
 * <p>Template Variables:
 * <ul>
 *   <li>{@code {resource_type}} - Resource type (e.g., MODEL, PAGE)</li>
 *   <li>{@code {resource_code}} - Resource code (e.g., model, publish)</li>
 * </ul>
 * 
 * <p>Role Types:
 * <ul>
 *   <li>TENANT_ADMIN - Full access to all resources</li>
 *   <li>DEVELOPER - Full access to development resources</li>
 *   <li>VIEWER - Read-only access to all resources</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @version 1.0.0
 * @since 2025-01-08
 */
@Getter
public enum RolePermissionTemplate {
    
    /**
     * Tenant Admin Role Template
     * 
     * <p>Full access to all resources within the tenant.
     * 
     * <p>permissions:
     * <ul>
     *   <li>manage - Full CRUD access</li>
     *   <li>read - Read access</li>
     * </ul>
     */
    TENANT_ADMIN("tenant_admin", List.of(
        "{resource_type}.{resource_code}.manage",
        "{resource_type}.{resource_code}.read"
    )),
    
    /**
     * Developer Role Template
     * 
     * <p>Full access to development resources.
     * 
     * <p>permissions:
     * <ul>
     *   <li>manage - Full CRUD access</li>
     *   <li>read - Read access</li>
     * </ul>
     */
    DEVELOPER("developer", List.of(
        "{resource_type}.{resource_code}.manage",
        "{resource_type}.{resource_code}.read"
    )),
    
    /**
     * Viewer Role Template
     * 
     * <p>Read-only access to all resources.
     * 
     * <p>permissions:
     * <ul>
     *   <li>read - Read access only</li>
     * </ul>
     */
    VIEWER("viewer", List.of(
        "{resource_type}.{resource_code}.read"
    ));
    
    /**
     * Role code (matches role code in database)
     */
    private final String roleCode;
    
    /**
     * Permission code templates
     * 
     * <p>Templates use placeholders:
     * <ul>
     *   <li>{@code {resource_type}} - Will be replaced with actual resource type</li>
     *   <li>{@code {resource_code}} - Will be replaced with actual resource code</li>
     * </ul>
     */
    private final List<String> permissionTemplates;
    
    /**
     * Constructor
     * 
     * @param roleCode Role code
     * @param permissionTemplates Permission code templates
     */
    RolePermissionTemplate(String roleCode, List<String> permissionTemplates) {
        this.roleCode = roleCode;
        this.permissionTemplates = permissionTemplates;
    }
    
    /**
     * Generate permission codes for a specific resource
     * 
     * <p>Replaces template variables with actual values:
     * <ul>
     *   <li>{@code {resource_type}} → resourceType</li>
     *   <li>{@code {resource_code}} → resourceCode</li>
     * </ul>
     * 
     * <p>Example:
     * <pre>
     * RolePermissionTemplate.TENANT_ADMIN.generatePermissions("model", "user_model")
     * → ["model.user_model.manage", "model.user_model.read"]
     * </pre>
     * 
     * @param resourceType Resource type (e.g., "model")
     * @param resourceCode Resource code (e.g., "user_model")
     * @return List of permission codes
     */
    public List<String> generatePermissions(String resourceType, String resourceCode) {
        return permissionTemplates.stream()
            .map(template -> template
                .replace("{resource_type}", resourceType)
                .replace("{resource_code}", resourceCode))
            .collect(Collectors.toList());
    }
    
    /**
     * Find template by role code
     * 
     * @param roleCode Role code to search for
     * @return RolePermissionTemplate or null if not found
     */
    public static RolePermissionTemplate findByRoleCode(String roleCode) {
        if (roleCode == null) {
            return null;
        }
        
        for (RolePermissionTemplate template : values()) {
            if (template.roleCode.equalsIgnoreCase(roleCode)) {
                return template;
            }
        }
        
        return null;
    }
    
    /**
     * Check if role code has a template
     * 
     * @param roleCode Role code to check
     * @return true if template exists, false otherwise
     */
    public static boolean hasTemplate(String roleCode) {
        return findByRoleCode(roleCode) != null;
    }
}
