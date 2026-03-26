package com.auraboot.framework.webhook.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Webhook subscription entity.
 *
 * @since 5.1.0
 */
@Data
@TableName(value = "ab_webhook_subscription", autoResultMap = true)
public class WebhookSubscription {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("pid")
    private String pid;

    @TableField("name")
    private String name;

    @TableField("target_url")
    private String targetUrl;

    @TableField("event_type")
    private String eventType;

    @TableField("model_code")
    private String modelCode;

    @TableField("filter_expression")
    private String filterExpression;

    @TableField("secret")
    private String secret;

    @TableField("headers")
    private String headers;

    @TableField("max_retries")
    private Integer maxRetries;

    @TableField("timeout_ms")
    private Integer timeoutMs;

    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
