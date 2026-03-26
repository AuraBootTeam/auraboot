package com.auraboot.framework.meta.entity;

import com.auraboot.framework.meta.entity.common.AbstractMultiVersionEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Invariant Definition entity.
 * Defines invariant rules (PRE/POST/ALWAYS) that are evaluated during command execution.
 * Versioned entity following the same pattern as StateGraphDefinition.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ab_invariant_definition")
public class InvariantDefinition extends AbstractMultiVersionEntity {

    @TableField("code")
    private String code;

    @TableField("display_name")
    private String displayName;

    @TableField("description")
    private String description;

    /**
     * SpEL expression to evaluate.
     */
    @TableField("expression")
    private String expression;

    /**
     * Invariant type: PRE / POST / ALWAYS.
     */
    @TableField("invariant_type")
    private String invariantType;

    /**
     * Severity: ERROR (blocks execution) / WARN (logs only).
     */
    @TableField("severity")
    private String severity;

    /**
     * Scope type: MODEL / COMMAND / STATE.
     */
    @TableField("scope_type")
    private String scopeType;

    /**
     * Scope reference: modelCode / commandCode / stateNodeCode.
     */
    @TableField("scope_ref")
    private String scopeRef;

    /**
     * Target model code for this invariant.
     */
    @TableField("model_code")
    private String modelCode;

    /**
     * Whether this invariant is enabled.
     */
    @TableField("enabled")
    private Boolean enabled;
}
