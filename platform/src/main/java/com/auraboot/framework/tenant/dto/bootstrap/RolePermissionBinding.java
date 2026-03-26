package com.auraboot.framework.tenant.dto.bootstrap;

import lombok.Data;
import java.util.List;

/**
 * Role-Permission Binding
 *
 * Defines the association between roles and permissions.
 *
 * @author AuraBoot
 * @since 2.2.0
 */
@Data
public class RolePermissionBinding {

    /**
     * Role code
     * References role code defined in RoleTemplate
     * Example: TENANT_OWNER, TENANT_ADMIN, TENANT_USER
     * Required field
     */
    private String roleCode;

    /**
     * Permission code list
     * List of permission codes that this role has
     *
     * Special values:
     * - "*" means having all permissions (usually for TENANT_OWNER)
     *
     * Example values:
     * - "model.model.manage" - Model management permission
     * - "page.page.read" - Page read permission
     *
     * Required field, must contain at least one permission code
     */
    private List<String> permissionCodes;


}
