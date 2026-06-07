package com.auraboot.framework.decision.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Decision Runtime definition — one logical catalogue entry per (tenant, decision_code).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@TableName(value = "ab_drt_definition", autoResultMap = true)
public class DrtDefinitionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("decision_code")
    private String decisionCode;

    @TableField("decision_name")
    private String decisionName;

    @TableField("description")
    private String description;

    /** Scoping hint: MODEL | WORKFLOW | GLOBAL | COMMAND */
    @TableField("scope_type")
    private String scopeType;

    /** Scoping reference value (e.g. a modelCode when scopeType=MODEL) */
    @TableField("scope_ref")
    private String scopeRef;

    /** Plugin / module that owns this decision (for grouping) */
    @TableField("owner_module")
    private String ownerModule;

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
