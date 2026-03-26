package com.auraboot.framework.notification.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Notification template entity.
 *
 * @since 5.1.0
 */
@Data
@TableName(value = "ab_notification_template", autoResultMap = true)
public class NotificationTemplate {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("pid")
    private String pid;

    @TableField("code")
    private String code;

    @TableField("name")
    private String name;

    /**
     * Channel: IN_APP / EMAIL / SMS.
     */
    @TableField("channel")
    private String channel;

    @TableField("subject_template")
    private String subjectTemplate;

    @TableField("body_template")
    private String bodyTemplate;

    /**
     * Available variable definitions as JSON.
     */
    @TableField(value = "variables", typeHandler = JsonbStringTypeHandler.class)
    private String variables;

    @TableField("enabled")
    private Boolean enabled;

    /**
     * JSON array of channel codes, e.g. ["in_app", "email"].
     * If null, falls back to the single {@link #channel} field.
     */
    @TableField("channels")
    private String channels;

    /**
     * Recipient resolution strategy: OPERATOR, RECORD_OWNER.
     * If null, defaults to OPERATOR.
     */
    @TableField("recipient_strategy")
    private String recipientStrategy;

    /**
     * Notification category: BUSINESS, SYSTEM, APPROVAL.
     * If null, defaults to BUSINESS.
     */
    @TableField("category")
    private String category;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
