package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.engine.model.FieldPermissionSet;

/**
 * Field Permission Service — resolves field-level visibility and editability
 * based on the member's roles and DSL field metadata.
 *
 * <p>Field permissions are defined in the DSL model field definitions
 * (via the {@code fieldPermission} property in field JSON extraProps),
 * NOT in a separate database table.
 */
public interface FieldPermissionService {

    /**
     * Get field permissions for a member on a specific model.
     *
     * <p>Implementation:
     * <ol>
     *   <li>Get member's role codes (from roleIds via roles)</li>
     *   <li>Get model's field definitions (from DSL metadata)</li>
     *   <li>For each field, check fieldPermission.view and fieldPermission.edit role lists</li>
     *   <li>If field has no fieldPermission, it is visible and editable (default)</li>
     *   <li>Multi-role: UNION (if any role allows view, the field is viewable)</li>
     * </ol>
     *
     * @param memberId  member (user) ID
     * @param modelCode model code
     * @return field permission set
     */
    FieldPermissionSet getFieldPermissions(Long memberId, String modelCode);
}
