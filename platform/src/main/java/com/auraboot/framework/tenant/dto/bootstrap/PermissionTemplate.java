package com.auraboot.framework.tenant.dto.bootstrap;

import lombok.Data;

/**
 * Permission Template
 *
 * Defines system-level permissions that should exist when initializing a tenant.
 *
 * @author AuraBoot
 * @since 2.2.0
 */
@Data
public class PermissionTemplate {

    /**
     * Permission code
     * Globally unique identifier for permission checking
     * Example: "model.model.manage", "page.page.read"
     */
    private String code;

    /**
     * Permission name
     * User-friendly display name
     * Example: "Model Management", "Page Read"
     */
    private String name;

    /**
     * Permission description
     * Detailed explanation of the permission's purpose and scope
     */
    private String description;

    /**
     * Permission type
     * Values: MENU, API, BUTTON
     * Default: MENU
     */
    private String type;

    /**
     * Module
     * Example: META, RBAC, TENANT
     */
    private String module;

    /**
     * Resource identifier
     * The resource path or identifier controlled by this permission
     * Example: "/meta/models", "entity:*"
     */
    private String resource;

    /**
     * Action type
     * Example: view, create, update, delete
     */
    private String action;

    /**
     * Resource type
     * Example: ENTITY, FIELD, DICT, API, MENU
     */
    private String resourceType;
}
