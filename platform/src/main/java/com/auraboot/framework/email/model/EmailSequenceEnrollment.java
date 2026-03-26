package com.auraboot.framework.email.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Tracks a contact's enrollment in an email sequence.
 *
 * @since 6.5.0
 */
@Data
@TableName("ab_email_sequence_enrollment")
public class EmailSequenceEnrollment {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("sequence_id")
    private Long sequenceId;

    /** The email account used to send from. */
    @TableField("account_id")
    private Long accountId;

    @TableField("contact_email")
    private String contactEmail;

    /** DSL model code of the associated CRM record (e.g. 'crm_contact'). */
    @TableField("model_code")
    private String modelCode;

    /** Primary key of the associated CRM record. */
    @TableField("record_id")
    private String recordId;

    /** Index of the next step to execute (0 = not started, 1 = first step sent, …). */
    @TableField("current_step")
    private Integer currentStep;

    /** Status: 'active', 'paused', 'completed', 'failed', 'unsubscribed'. */
    @TableField("status")
    private String status;

    /** Timestamp when the next sequence step should be sent. */
    @TableField("next_send_at")
    private Instant nextSendAt;

    @TableField("enrolled_at")
    private Instant enrolledAt;

    @TableField("completed_at")
    private Instant completedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
