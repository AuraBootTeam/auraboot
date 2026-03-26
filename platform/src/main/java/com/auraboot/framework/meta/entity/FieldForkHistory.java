package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Field fork history entity
 * Maps to table: ab_field_fork_history
 * 
 * Tracks field fork operations for traceability
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_field_fork_history", autoResultMap = true)
public class FieldForkHistory {

    /**
     * Primary key
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Tenant ID
     */
    @TableField("tenant_id")
    private Long tenantId;

    /**
     * Original field ID
     * References ab_meta_field(id)
     */
    @TableField("original_field_id")
    private Long originalFieldId;

    /**
     * Original field PID (for convenience)
     */
    @TableField(exist = false)
    private String originalFieldPid;

    /**
     * Original field code (for convenience)
     */
    @TableField(exist = false)
    private String originalFieldCode;

    /**
     * Forked field ID
     * References ab_meta_field(id)
     */
    @TableField("forked_field_id")
    private Long forkedFieldId;

    /**
     * Forked field PID (for convenience)
     */
    @TableField(exist = false)
    private String forkedFieldPid;

    /**
     * Forked field code (for convenience)
     */
    @TableField(exist = false)
    private String forkedFieldCode;

    /**
     * Fork reason
     * Explanation for why this fork was created
     */
    @TableField("fork_reason")
    private String forkReason;

    /**
     * Forked by (user identifier)
     */
    @TableField("forked_by")
    private String forkedBy;

    /**
     * Forked at timestamp
     */
    @TableField("forked_at")
    private Instant forkedAt;
}
