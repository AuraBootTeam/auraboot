package com.auraboot.framework.cloudconfig.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Cloud vendor configuration entity with PLATFORM/TENANT layering.
 * <p>
 * PLATFORM-level configs have {@code tenantId = null} and serve as defaults.
 * TENANT-level configs override PLATFORM configs for a specific tenant.
 *
 * @since 6.3.0
 */
@Data
@TableName(value = "ab_cloud_config", autoResultMap = true)
public class CloudConfig {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("pid")
    private String pid;

    /** PLATFORM | TENANT */
    @TableField("config_level")
    private String configLevel;

    @TableField("tenant_id")
    private Long tenantId;

    /** sms | email | oauth | storage | cdn | llm */
    @TableField("service_type")
    private String serviceType;

    /** Provider identifier: tencent_sms, aliyun_sms, google, apple, anthropic, openai, etc. */
    @TableField("provider_code")
    private String providerCode;

    /** JSON configuration blob (sensitive fields are encrypted with ENC: prefix) */
    @TableField(value = "config", typeHandler = JsonbStringTypeHandler.class)
    private String config;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("priority")
    private Integer priority;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("deleted_flag")
    @TableLogic
    private Boolean deletedFlag;
}
