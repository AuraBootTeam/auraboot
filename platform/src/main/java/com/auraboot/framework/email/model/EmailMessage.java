package com.auraboot.framework.email.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * An email message synced from Gmail.
 *
 * <p>JSONB columns (toAddresses, ccAddresses, bccAddresses, attachments, labelIds)
 * are stored as raw JSON strings.</p>
 *
 * @since 6.5.0
 */
@Data
@TableName("ab_email_message")
public class EmailMessage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("account_id")
    private Long accountId;

    /** Gmail's unique message ID. */
    @TableField("gmail_message_id")
    private String gmailMessageId;

    /** Gmail thread ID this message belongs to. */
    @TableField("gmail_thread_id")
    private String gmailThreadId;

    /** Direction: 'inbound' or 'outbound'. See {@link EmailConstants#DIRECTION_INBOUND}. */
    @TableField("direction")
    private String direction;

    @TableField("from_address")
    private String fromAddress;

    @TableField("from_name")
    private String fromName;

    /** JSON array of recipient email addresses. */
    @TableField("to_addresses")
    private String toAddresses;

    /** JSON array of CC email addresses. */
    @TableField("cc_addresses")
    private String ccAddresses;

    /** JSON array of BCC email addresses. */
    @TableField("bcc_addresses")
    private String bccAddresses;

    @TableField("subject")
    private String subject;

    @TableField("body_text")
    private String bodyText;

    @TableField("body_html")
    private String bodyHtml;

    @TableField("has_attachments")
    private Boolean hasAttachments;

    /** JSON array of attachment metadata objects. */
    @TableField("attachments")
    private String attachments;

    /** JSON array of Gmail label IDs (e.g. INBOX, SENT). */
    @TableField("label_ids")
    private String labelIds;

    @TableField("is_read")
    private Boolean isRead;

    /** Original timestamp of the email as reported by Gmail. */
    @TableField("gmail_date")
    private Instant gmailDate;

    @TableField("synced_at")
    private Instant syncedAt;

    /** User ID the message is assigned to (for shared mailboxes). */
    @TableField("assigned_to")
    private Long assignedTo;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
