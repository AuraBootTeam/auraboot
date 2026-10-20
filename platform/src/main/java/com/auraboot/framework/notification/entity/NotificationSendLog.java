package com.auraboot.framework.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Notification send log entity.
 *
 * @since 5.1.0
 */
@Data
@TableName("ab_notification_send_log")
public class NotificationSendLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("template_code")
    private String templateCode;

    /**
     * Channel: IN_APP / EMAIL / SMS.
     */
    @TableField("channel")
    private String channel;

    @TableField("recipient")
    private String recipient;

    @TableField("subject")
    private String subject;

    @TableField("content")
    private String content;

    /**
     * Status: PENDING / SENT / FAILED.
     */
    @TableField("status")
    private String status;

    @TableField("error_message")
    private String errorMessage;

    @TableField("sent_at")
    private Instant sentAt;

    private String deliveryKey;
    private String requestHash;
    private String resolutionReference;
    private String configurationHash;
    @TableField(value = "request_snapshot", jdbcType = org.apache.ibatis.type.JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String requestSnapshot;
    private String attemptFence;
    private Instant attemptStartedAt;
    private Instant attemptLeaseUntil;
    private Instant finishedAt;
    private Long providerCode;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
