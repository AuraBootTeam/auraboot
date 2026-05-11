package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * Separation of Duties (SoD) Violation Log entity.
 * Records every SoD conflict detected during command execution,
 * along with the enforcement outcome (BLOCKED, WARNED, LOGGED).
 *
 * @author AuraBoot Team
 * @since 6.2.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_sod_violation_log")
public class SodViolationLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("rule_id")
    private Long ruleId;

    @TableField("rule_code")
    private String ruleCode;

    @TableField("actor_id")
    private Long actorId;

    @TableField("actor_name")
    private String actorName;

    /**
     * The command the actor was trying to execute.
     */
    @TableField("command_attempted")
    private String commandAttempted;

    /**
     * The conflicting command that the same actor previously executed.
     */
    @TableField("conflicting_command")
    private String conflictingCommand;

    /**
     * The actor who executed the conflicting command (same as actorId for SoD).
     */
    @TableField("conflicting_actor_id")
    private Long conflictingActorId;

    @TableField("entity_type")
    private String entityType;

    @TableField("entity_id")
    private Long entityId;

    @TableField("entity_pid")
    private String entityPid;

    /**
     * Enforcement level at the time of violation: HARD, SOFT, AUDIT_ONLY.
     */
    @TableField("enforcement")
    private String enforcement;

    /**
     * Outcome of the enforcement: BLOCKED, WARNED, LOGGED.
     */
    @TableField("outcome")
    private String outcome;

    /**
     * User who overrode the violation (if applicable).
     */
    @TableField("override_by")
    private Long overrideBy;

    /**
     * Reason for overriding the violation.
     */
    @TableField("override_reason")
    private String overrideReason;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
