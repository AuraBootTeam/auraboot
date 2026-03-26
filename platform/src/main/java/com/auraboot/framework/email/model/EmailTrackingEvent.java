package com.auraboot.framework.email.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Records an email open, click, or bounce tracking event.
 *
 * @since 6.5.0
 */
@Data
@TableName("ab_email_tracking_event")
public class EmailTrackingEvent {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("message_id")
    private Long messageId;

    /** Unique tracking pixel / link token embedded in the outbound email. */
    @TableField("tracking_id")
    private String trackingId;

    /** Event type: 'open', 'click', or 'bounce'. See {@link EmailConstants#TRACKING_OPEN}. */
    @TableField("event_type")
    private String eventType;

    /** Original URL that was clicked (null for open/bounce events). */
    @TableField("link_url")
    private String linkUrl;

    @TableField("ip_address")
    private String ipAddress;

    @TableField("user_agent")
    private String userAgent;

    @TableField("event_at")
    private Instant eventAt;
}
