package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * Separation of Duties (SoD) Rule entity.
 * Defines a pair of conflicting commands that should not be executed
 * by the same actor on the same entity (or within the same model/global scope).
 *
 * @author AuraBoot Team
 * @since 6.2.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_sod_rule")
public class SodRule {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("rule_code")
    private String ruleCode;

    @TableField("rule_name")
    private String ruleName;

    @TableField("description")
    private String description;

    /**
     * First command code in the conflicting pair (e.g. pe:create_purchase_order).
     */
    @TableField("command_a")
    private String commandA;

    /**
     * Second command code in the conflicting pair (e.g. pe:approve_purchase_order).
     */
    @TableField("command_b")
    private String commandB;

    /**
     * Scope of entity matching: SAME_RECORD, SAME_MODEL, or GLOBAL.
     */
    @TableField("entity_scope")
    private String entityScope;

    /**
     * Enforcement level: HARD (block), SOFT (warn + audit), AUDIT_ONLY (log only).
     */
    @TableField("enforcement")
    private String enforcement;

    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField("created_by")
    private Long createdBy;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("deleted_flag")
    @TableLogic
    private Boolean deletedFlag;
}
