package com.auraboot.framework.notification.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Notification rule entity — defines when and how to send notifications.
 *
 * <p>A rule has three main sections:
 * <ul>
 *   <li><b>Trigger</b>: EVENT (record lifecycle) or SCHEDULED (cron-based)</li>
 *   <li><b>Condition</b>: DSL filter applied to a model's records</li>
 *   <li><b>Action</b>: which channel+template to use and who receives it</li>
 * </ul>
 *
 * @since 5.2.0
 */
@Data
@TableName(value = "ab_notification_rule", autoResultMap = true)
public class NotificationRule {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    /** Human-readable unique code, e.g. "overdue-payment-alert". */
    @TableField("code")
    private String code;

    /** Display name. */
    @TableField("name")
    private String name;

    /** Optional description for this rule. */
    @TableField("description")
    private String description;

    /** Whether this rule is active. */
    @TableField("enabled")
    private Boolean enabled;

    /**
     * Trigger type: EVENT or SCHEDULED.
     * EVENT fires on record lifecycle changes.
     * SCHEDULED fires periodically via cron.
     */
    @TableField("trigger_type")
    private String triggerType;

    /**
     * Trigger configuration as JSON.
     * For EVENT: {"event": "created"|"updated"|"deleted", "modelCode": "..."}
     * For SCHEDULED: {"schedule": "hourly"|"daily"|"weekly", "hour": 9, "minute": 0}
     */
    @TableField(value = "trigger_config", typeHandler = JsonbStringTypeHandler.class)
    private String triggerConfig;

    /**
     * Model code to query when evaluating conditions.
     * E.g., "fin_ar_invoice", "inv_item".
     */
    @TableField("condition_model_code")
    private String conditionModelCode;

    /**
     * Filter conditions as JSON array: [{fieldName, operator, value}, ...].
     * Applied against the condition model when evaluating the rule.
     */
    @TableField(value = "condition_filter", typeHandler = JsonbStringTypeHandler.class)
    private String conditionFilter;

    /**
     * Notification channel: IN_APP / EMAIL / WEBHOOK.
     */
    @TableField("action_channel")
    private String actionChannel;

    /**
     * Template code to use for the notification body.
     */
    @TableField("action_template_code")
    private String actionTemplateCode;

    /**
     * How to determine recipients: OPERATOR / RECORD_OWNER / SPECIFIC_USERS.
     */
    @TableField("recipient_type")
    private String recipientType;

    /**
     * Recipient field name on the model (for RECORD_OWNER strategy),
     * or comma-separated user IDs (for SPECIFIC_USERS strategy).
     */
    @TableField("recipient_field")
    private String recipientField;

    /** Timestamp of last successful evaluation. */
    @TableField("last_evaluated_at")
    private Instant lastEvaluatedAt;

    /** Number of notifications sent by this rule (lifetime). */
    @TableField("send_count")
    private Integer sendCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
