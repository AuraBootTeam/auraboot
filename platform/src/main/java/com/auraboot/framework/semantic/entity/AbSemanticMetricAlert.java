package com.auraboot.framework.semantic.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * A threshold alert over a published semantic metric (BI rectification R2).
 *
 * <p>The evaluator re-runs the metric through the governed semantic query
 * pipeline as the alert creator — if the creator lost access to the metric,
 * evaluation fails closed (no notification) instead of leaking governed data.
 */
@Data
@TableName(value = "ab_semantic_metric_alert", autoResultMap = true)
public class AbSemanticMetricAlert {

    @TableId
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    private String name;

    @TableField("metric_pid")
    private String metricPid;

    /** gt | gte | lt | lte */
    private String comparator;

    private java.math.BigDecimal threshold;

    @TableField("silence_minutes")
    private Integer silenceMinutes;

    /** active | paused */
    @TableField("alert_status")
    private String alertStatus;

    @TableField("last_triggered_at")
    private OffsetDateTime lastTriggeredAt;

    @TableField("last_evaluated_at")
    private OffsetDateTime lastEvaluatedAt;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;

    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
