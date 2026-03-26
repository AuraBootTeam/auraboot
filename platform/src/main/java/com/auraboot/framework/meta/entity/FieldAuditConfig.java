package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Configuration entry specifying which field on which model
 * should be tracked by the field change audit system.
 *
 * @since 6.2.0
 */
@Data
@TableName("ab_field_audit_config")
public class FieldAuditConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("model_code")
    private String modelCode;

    @TableField("field_code")
    private String fieldCode;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("require_reason")
    private Boolean requireReason;

    @TableField("notify_on_change")
    private Boolean notifyOnChange;

    @TableField("created_at")
    private Instant createdAt;
}
