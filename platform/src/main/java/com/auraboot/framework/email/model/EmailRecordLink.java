package com.auraboot.framework.email.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Links an email message (or entire thread) to a CRM record.
 *
 * <p>When {@code messageId} is null, the link applies to the entire thread
 * identified by {@code threadId}.</p>
 *
 * @since 6.5.0
 */
@Data
@TableName("ab_email_record_link")
public class EmailRecordLink {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    /** FK to ab_email_message; may be null for thread-level links. */
    @TableField("message_id")
    private Long messageId;

    /** Gmail thread ID for thread-level links. */
    @TableField("thread_id")
    private String threadId;

    /** DSL model code of the linked CRM entity (e.g. 'crm_contact'). */
    @TableField("model_code")
    private String modelCode;

    /** Primary key of the linked CRM record (stored as string for flexibility). */
    @TableField("record_id")
    private String recordId;

    /** How the link was created: 'auto' (system-detected) or 'manual' (user-created). */
    @TableField("link_type")
    private String linkType;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
