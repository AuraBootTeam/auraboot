package com.auraboot.framework.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * In-app notification entity.
 *
 * @since 5.1.0
 */
@Data
@TableName("ab_notification")
public class Notification {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    /**
     * Category: SYSTEM / APPROVAL / ALERT / BUSINESS.
     */
    @TableField("category")
    private String category;

    /**
     * Priority: LOW / NORMAL / HIGH / URGENT.
     */
    @TableField("priority")
    private String priority;

    @TableField("source_type")
    private String sourceType;

    @TableField("source_id")
    private String sourceId;

    @TableField("is_read")
    private Boolean isRead;

    @TableField("read_at")
    private Instant readAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
