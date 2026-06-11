package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * Data Permission Policy entity.
 * Defines row-level (data scope) and column-level (field masking) access policies.
 *
 * @since 5.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "ab_data_permission_policy", autoResultMap = true)
public class DataPermissionPolicy {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("pid")
    private String pid;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("model_code")
    private String modelCode;

    /**
     * Policy type: ROW or COLUMN.
     */
    @TableField("policy_type")
    private String policyType;

    /**
     * Row scope type: ALL / SELF / DEPARTMENT / CUSTOM.
     * Only for ROW policies.
     */
    @TableField("scope_type")
    private String scopeType;

    /**
     * Legacy free-form SQL fragment for custom row filtering (deprecated; prefer {@link #conditionAst}).
     * e.g. "#record['created_by'] == #user.id"
     */
    @TableField("scope_expression")
    private String scopeExpression;

    /**
     * Structured condition AST (decision {@code ConditionNode} JSON) for CUSTOM row scope.
     * Compiled to SQL by {@code ConditionToSqlBuilder} (allowlist). Takes precedence over
     * {@link #scopeExpression} when present.
     */
    @TableField(value = "condition_ast", typeHandler = JacksonTypeHandler.class)
    private Object conditionAst;

    /**
     * Target field code for COLUMN policies.
     */
    @TableField("field_code")
    private String fieldCode;

    /**
     * Mask type: HIDE / PARTIAL / HASH / CUSTOM.
     * Only for COLUMN policies.
     */
    @TableField("mask_type")
    private String maskType;

    /**
     * Custom mask expression for CUSTOM mask type.
     */
    @TableField("mask_expression")
    private String maskExpression;

    @TableField("priority")
    private Integer priority;

    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
