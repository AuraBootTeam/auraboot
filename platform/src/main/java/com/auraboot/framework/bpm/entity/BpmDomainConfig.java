package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Entity for BPM domain configuration.
 * Defines domain-specific task list views with custom fields, filters, and sorting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_bpm_domain_config", autoResultMap = true)
public class BpmDomainConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Unique identifier (ULID).
     */
    private String pid;

    /**
     * Tenant ID for multi-tenancy isolation.
     */
    private Long tenantId;

    /**
     * Unique domain code within tenant.
     */
    private String domainCode;

    /**
     * Domain display name.
     */
    private String domainName;

    /**
     * Associated model code.
     */
    private String modelCode;

    /**
     * Process keys associated with this domain.
     */
    @TableField(typeHandler = PluginSettingsTypeHandler.class)
    private List<String> processKeys;

    /**
     * Fields to display in the task list.
     */
    @TableField(typeHandler = PluginSettingsTypeHandler.class)
    private List<Map<String, Object>> listFields;

    /**
     * @deprecated Planned for card-based inbox with per-domain column filtering,
     * but card layout uses universal filters (domain/status/priority) instead.
     * Kept for backward compatibility; not consumed by frontend.
     */
    @Deprecated
    @TableField(typeHandler = PluginSettingsTypeHandler.class)
    private List<Map<String, Object>> filterFields;

    /**
     * @deprecated Same reason as filterFields — card-based inbox uses universal
     * sort options (newest/oldest/deadline) instead of per-domain sort columns.
     * Kept for backward compatibility; not consumed by frontend.
     */
    @Deprecated
    @TableField(typeHandler = PluginSettingsTypeHandler.class)
    private List<Map<String, Object>> sortFields;

    /**
     * Whether this domain config is enabled.
     */
    @Builder.Default
    private Boolean enabled = true;

    /**
     * Soft delete flag.
     */
    @Builder.Default
    @TableLogic
    private Boolean deletedFlag = false;

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
     * User who created the record.
     */
    private Long createdBy;

    /**
     * User who last updated the record.
     */
    private Long updatedBy;
}
