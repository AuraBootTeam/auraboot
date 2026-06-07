package com.auraboot.framework.eventpolicy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Event Policy definition — one logical catalogue entry per (tenant, policy_code).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@TableName(value = "ab_drt_policy_definition", autoResultMap = true)
public class DrtPolicyDefinitionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("policy_code")
    private String policyCode;

    @TableField("policy_name")
    private String policyName;

    /** Event type that triggers this policy, e.g. FORM_SUBMITTED */
    @TableField("event_type")
    private String eventType;

    /** Target entity type, e.g. FORM */
    @TableField("target_type")
    private String targetType;

    /** Target instance key, e.g. a model code or form code */
    @TableField("target_key")
    private String targetKey;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("created_by")
    private String createdBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("updated_at")
    private Instant updatedAt;
}
