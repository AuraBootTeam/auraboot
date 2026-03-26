package com.auraboot.framework.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * User notification preference entity.
 * Opt-out model: all channels enabled by default.
 * Only explicit records with enabled=false disable a channel+category.
 *
 * @since 6.0.0
 */
@Data
@TableName("ab_notification_preference")
public class NotificationPreference {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    @TableField("channel")
    private String channel;

    @TableField("category")
    private String category;

    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
