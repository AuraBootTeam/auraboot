package com.auraboot.framework.webhook.entity;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Webhook delivery log entity.
 *
 * @since 5.1.0
 */
@Data
@TableName("ab_webhook_delivery_log")
public class WebhookDeliveryLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("subscription_pid")
    private String subscriptionPid;

    @TableField("event_id")
    private String eventId;

    @TableField("request_url")
    private String requestUrl;

    @TableField("request_body")
    private String requestBody;

    @TableField("response_status")
    private Integer responseStatus;

    @TableField("response_body")
    private String responseBody;

    /**
     * Delivery status: SUCCESS / FAILED / PENDING.
     */
    @TableField("delivery_status")
    private String deliveryStatus;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("error_message")
    private String errorMessage;

    @TableField("delivered_at")
    private Instant deliveredAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
