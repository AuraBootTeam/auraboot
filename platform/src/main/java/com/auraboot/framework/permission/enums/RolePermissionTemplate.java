package com.auraboot.framework.permission.enums;

import com.auraboot.framework.permission.entity.Permission;
import lombok.Getter;

import java.util.function.Predicate;

/**
 * Role Permission Template Enum
 *
 * <p>Defines permission assignment filters for different role types.
 * Uses filter-based approach: each role has a predicate that determines
 * whether a given permission should be assigned to that role.
 *
 * <p>Filter types:
 * <ul>
 *   <li>ALL — assign all permissions (module, resource, action nodes)</li>
 *   <li>READ_ONLY — assign only "read" action nodes + all non-leaf nodes (modules, resources)</li>
 * </ul>
 *
 * @author AuraBoot Platform
 * @version 2.0.0
 * @since 2025-01-08
 */
@Getter
public enum RolePermissionTemplate {

    /**
     * Tenant Admin Role — full access to all permissions.
     */
    TENANT_ADMIN("tenant_admin", PermissionFilter.ALL),

    /**
     * Developer Role — full access to all permissions.
     */
    DEVELOPER("developer", PermissionFilter.ALL),

    /**
     * Viewer Role — read-only action nodes + all non-leaf (module/resource) nodes.
     */
    VIEWER("viewer", PermissionFilter.READ_ONLY);

    /**
     * Role code (matches role code in database).
     */
    private final String roleCode;

    /**
     * Permission filter predicate.
     */
    private final Predicate<Permission> filter;

    RolePermissionTemplate(String roleCode, Predicate<Permission> filter) {
        this.roleCode = roleCode;
        this.filter = filter;
    }

    /**
     * Determine whether a permission should be assigned to this role.
     *
     * @param permission Permission to check
     * @return true if the permission should be assigned
     */
    public boolean shouldAssign(Permission permission) {
        return filter.test(permission);
    }

    /**
     * Find template by role code.
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
     * Check if role code has a template.
     *
     * @param roleCode Role code to check
     * @return true if template exists
     */
    public static boolean hasTemplate(String roleCode) {
        return findByRoleCode(roleCode) != null;
    }

    // ========================================================================
    // Permission Filters
    // ========================================================================

    /**
     * Permission filter predicates for role assignment.
     */
    private static final class PermissionFilter {

        /**
         * ALL — always returns true, assigns all permissions.
         */
        static final Predicate<Permission> ALL = permission -> true;

        /**
         * READ_ONLY — assigns "read" action nodes + all non-leaf nodes.
         * Non-leaf nodes (level != 3 or level == null) are always included
         * so the hierarchy tree remains navigable for viewers.
         */
        static final Predicate<Permission> READ_ONLY = permission -> {
            Integer level = permission.getLevel();
            // Non-leaf nodes (module/resource) are always assigned
            if (level == null || level != 3) {
                return true;
            }
            // Leaf nodes: only "read" action
            return "read".equals(permission.getAction());
        };
    }
}
