package com.auraboot.framework.plugin.entity;

import com.auraboot.framework.plugin.dto.imports.OwnershipType;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Entity for tracking resources created by plugins.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_plugin_resource")
public class PluginResource {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Unique resource tracking identifier (ULID).
     */
    private String pid;

    /**
     * Tenant ID for multi-tenancy isolation.
     */
    private Long tenantId;

    /**
     * Reference to ab_plugin.pid.
     */
    private String pluginPid;

    /**
     * Reference to ab_plugin_import_history.import_id.
     */
    private String importId;

    /**
     * Type of resource.
     */
    private String resourceType;

    /**
     * PID of the created resource.
     */
    private String resourcePid;

    /**
     * ID of the created resource.
     */
    private Long resourceId;

    /**
     * Code of the resource.
     */
    private String resourceCode;

    /**
     * Name of the resource (for display).
     */
    private String resourceName;

    /**
     * Action performed.
     */
    private String action;

    /**
     * Previous state for rollback.
     */
    @TableField(typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> previousState;

    /**
     * Current state after import.
     */
    @TableField(typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> currentState;

    /**
     * Ownership type controlling resource lifecycle.
     * PLUGIN_OWNED: Plugin has full control, deleted on uninstall
     * SHARED: Plugin created, user can modify, prompt on uninstall if modified
     * USER_CLAIMED: User has taken ownership, not affected by plugin lifecycle
     */
    @Builder.Default
    private String ownershipType = "shared";

    /**
     * Complete snapshot at import time for detecting user modifications.
     */
    @TableField(typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> importSnapshot;

    /**
     * Whether user has modified this resource after import.
     */
    @Builder.Default
    private Boolean userModified = false;

    /**
     * Timestamp when user first modified this resource.
     */
    private Instant userModifiedAt;

    /**
     * Plugin version when this resource was last synced.
     */
    private String lastSyncVersion;

    /**
     * Sequence for ordered operations.
     */
    @Builder.Default
    private Integer sequence = 0;

    /**
     * Record creation time.
     */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    /**
     * Record update time.
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    /**
     * Get resource type as enum.
     */
    public ResourceType getResourceTypeEnum() {
        return resourceType != null ? ResourceType.fromCode(resourceType) : null;
    }

    /**
     * Set resource type from enum.
     */
    public void setResourceTypeEnum(ResourceType type) {
        this.resourceType = type != null ? type.code() : null;
    }

    /**
     * Get action as enum.
     */
    public ResourceAction getActionEnum() {
        return action != null ? ResourceAction.fromCode(action) : null;
    }

    /**
     * Set action from enum.
     */
    public void setActionEnum(ResourceAction actionEnum) {
        this.action = actionEnum != null ? actionEnum.code() : null;
    }

    /**
     * Get ownership type as enum.
     */
    public OwnershipType getOwnershipTypeEnum() {
        return ownershipType != null ? OwnershipType.fromCode(ownershipType) : OwnershipType.SHARED;
    }

    /**
     * Set ownership type from enum.
     */
    public void setOwnershipTypeEnum(OwnershipType type) {
        this.ownershipType = type != null ? type.code() : OwnershipType.SHARED.code();
    }

    /**
     * Check if this resource can be modified by users.
     */
    public boolean allowsUserModification() {
        return getOwnershipTypeEnum().allowsUserModification();
    }

    /**
     * Check if this resource is still managed by the plugin.
     */
    public boolean isManagedByPlugin() {
        return getOwnershipTypeEnum().isManagedByPlugin();
    }

    /**
     * Mark this resource as modified by user.
     */
    public void markAsUserModified() {
        if (!Boolean.TRUE.equals(this.userModified)) {
            this.userModified = true;
            this.userModifiedAt = Instant.now();
        }
    }

    /**
     * Transfer ownership to user (detach from plugin management).
     */
    public void claimByUser() {
        this.ownershipType = OwnershipType.USER_CLAIMED.name().toLowerCase();
        markAsUserModified();
    }
}
