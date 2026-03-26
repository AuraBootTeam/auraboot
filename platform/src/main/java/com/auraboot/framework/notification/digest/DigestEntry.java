package com.auraboot.framework.notification.digest;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Notification digest entry entity.
 * Aggregates same-type notifications within a time window for batch delivery.
 *
 * @since 6.0.0
 */
@Data
@TableName("ab_notification_digest")
public class DigestEntry {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    @TableField("channel")
    private String channel;

    @TableField("template_code")
    private String templateCode;

    @TableField("category")
    private String category;

    @TableField("count")
    private Integer count;

    @TableField("window_start")
    private Instant windowStart;

    @TableField("window_end")
    private Instant windowEnd;

    @TableField("flushed")
    private Boolean flushed;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
