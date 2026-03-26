package com.auraboot.framework.email.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * An email sequence (drip campaign) containing ordered steps.
 *
 * @since 6.5.0
 */
@Data
@TableName("ab_email_sequence")
public class EmailSequence {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    /** Status: 'draft', 'active', 'paused', 'archived'. See {@link EmailConstants#SEQ_STATUS_ACTIVE}. */
    @TableField("status")
    private String status;

    @TableField("created_by")
    private Long createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField("deleted_flag")
    @TableLogic
    private Boolean deletedFlag;
}
