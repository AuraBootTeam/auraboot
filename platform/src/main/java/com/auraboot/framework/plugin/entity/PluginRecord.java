package com.auraboot.framework.plugin.entity;

import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.PluginStatus;
import com.auraboot.framework.plugin.typehandler.PluginManifestTypeHandler;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.Map;

/**
 * Plugin record entity.
 * Corresponds to table: ab_plugin
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_plugin", autoResultMap = true)
public class PluginRecord {

    /**
     * Primary key (auto-generated).
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Business unique identifier (ULID).
     */
    @TableField("pid")
    private String pid;

    /**
     * Tenant ID for multi-tenancy isolation.
     */
    @TableField("tenant_id")
    private Long tenantId;

    /**
     * Plugin unique identifier (e.g., "com.example.my-plugin").
     */
    @TableField("plugin_id")
    private String pluginId;

    /**
     * Plugin namespace for resource isolation.
     */
    @TableField("namespace")
    private String namespace;

    /**
     * Plugin version (semver format).
     */
    @TableField("version")
    private String version;

    /**
     * Human-readable display name.
     */
    @TableField("display_name")
    private String displayName;

    /**
     * Plugin description.
     */
    @TableField("description")
    private String description;

    /**
     * Plugin author or organization.
     */
    @TableField("author")
    private String author;

    /**
     * Current plugin status.
     */
    @TableField("status")
    private String status;

    /**
     * Plugin manifest (JSONB).
     */
    @TableField(value = "manifest", typeHandler = PluginManifestTypeHandler.class, jdbcType = JdbcType.OTHER)
    private PluginManifest manifest;

    /**
     * Plugin runtime settings (JSONB).
     */
    @TableField(value = "settings", typeHandler = PluginSettingsTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> settings;

    /**
     * Installation timestamp.
     */
    @TableField("installed_at")
    private Instant installedAt;

    /**
     * Last enabled timestamp.
     */
    @TableField("enabled_at")
    private Instant enabledAt;

    /**
     * Last disabled timestamp.
     */
    @TableField("disabled_at")
    private Instant disabledAt;

    /**
     * Record creation timestamp.
     */
    @TableField("created_at")
    private Instant createdAt;

    /**
     * Record update timestamp.
     */
    @TableField("updated_at")
    private Instant updatedAt;

    /**
     * Logical delete flag.
     */
    @TableField("deleted_flag")
    @TableLogic(value = "false", delval = "true")
    private Boolean deletedFlag;

    // ========== Unified Plugin Package Fields ==========

    /**
     * Whether this plugin has configuration (DSL) resources.
     */
    @TableField("has_config")
    private Boolean hasConfig;

    /**
     * Whether this plugin has a backend JAR (PF4J plugin).
     */
    @TableField("has_backend")
    private Boolean hasBackend;

    /**
     * Whether this plugin has frontend components (Module Federation).
     */
    @TableField("has_frontend")
    private Boolean hasFrontend;

    /**
     * URL to the frontend remoteEntry.js for Module Federation.
     */
    @TableField("frontend_remote_url")
    private String frontendRemoteUrl;

    /**
     * PF4J plugin ID if backend is loaded.
     */
    @TableField("backend_plugin_id")
    private String backendPluginId;

    /**
     * Backend plugin status: LOADED, STARTED, STOPPED, FAILED.
     */
    @TableField("backend_status")
    private String backendStatus;

    /**
     * Backend error message if failed.
     */
    @TableField("backend_error")
    private String backendError;

    /**
     * Frontend plugin status: DEPLOYED, LOADED, FAILED.
     */
    @TableField("frontend_status")
    private String frontendStatus;

    /**
     * Frontend error message if failed.
     */
    @TableField("frontend_error")
    private String frontendError;

    /**
     * Get status as enum.
     */
    public PluginStatus getStatusEnum() {
        if (status == null) {
            return null;
        }
        return PluginStatus.fromCode(status);
    }

    /**
     * Set status from enum.
     */
    public void setStatusEnum(PluginStatus statusEnum) {
        this.status = statusEnum != null ? statusEnum.code() : null;
    }

    /**
     * Check if plugin is enabled.
     */
    public boolean isEnabled() {
        return PluginStatus.ENABLED.code().equals(status);
    }

    /**
     * Check if plugin is in failed state.
     */
    public boolean isFailed() {
        return PluginStatus.FAILED.code().equals(status);
    }
}
