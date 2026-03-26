package com.auraboot.framework.plugin.entity;

import com.auraboot.framework.plugin.dto.imports.ImportStatus;
import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Entity for plugin import history tracking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_plugin_import_history")
public class PluginImportHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Unique import operation identifier (ULID).
     */
    private String importId;

    /**
     * Tenant ID for multi-tenancy isolation.
     */
    private Long tenantId;

    /**
     * Reference to ab_plugin.pid after successful import.
     */
    private String pluginPid;

    /**
     * Plugin identifier from manifest.
     */
    private String pluginId;

    /**
     * Plugin namespace.
     */
    private String namespace;

    /**
     * Plugin version.
     */
    private String version;

    /**
     * Import status.
     */
    private String status;

    /**
     * Import type: INSTALL, UPGRADE, ROLLBACK.
     */
    private String importType;

    /**
     * Source type: JSON, ZIP, URL.
     */
    private String sourceType;

    /**
     * Source name (filename or URL).
     */
    private String sourceName;

    /**
     * Import options as JSON.
     */
    @TableField(typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> options;

    /**
     * Complete manifest snapshot for rollback.
     */
    @TableField(typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> manifestSnapshot;

    /**
     * Import result details.
     */
    @TableField(typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> result;

    /**
     * Resource summary (counts by type).
     */
    @TableField(typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> resourceSummary;

    /**
     * Quality score breakdown (5 dimensions: completeness, semanticRichness, agentReadiness, safety, i18n).
     */
    @TableField(typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> qualityScore;

    /**
     * Error message if failed.
     */
    private String errorMessage;

    /**
     * Detailed error information.
     */
    private String errorDetail;

    /**
     * Import start time.
     */
    private Instant startedAt;

    /**
     * Import completion time.
     */
    private Instant completedAt;

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
     * User who initiated the import.
     */
    private Long createdBy;

    /**
     * Get status as enum.
     */
    public ImportStatus getStatusEnum() {
        return status != null ? ImportStatus.fromCode(status) : null;
    }

    /**
     * Set status from enum.
     */
    public void setStatusEnum(ImportStatus statusEnum) {
        this.status = statusEnum != null ? statusEnum.code() : null;
    }

    /**
     * Check if import is still in progress.
     */
    public boolean isInProgress() {
        ImportStatus s = getStatusEnum();
        return s != null && s.isInProgress();
    }

    /**
     * Check if import was successful.
     */
    public boolean isSuccess() {
        return ImportStatus.SUCCESS.code().equals(status);
    }

    /**
     * Check if import failed.
     */
    public boolean isFailed() {
        return ImportStatus.FAILED.code().equals(status);
    }
}
