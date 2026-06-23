package com.auraboot.framework.behavior.outcome;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

@Data
@TableName(value = "ab_behavior_outcome_outbox", autoResultMap = true)
public class BehaviorOutcomeOutbox {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("event_id")
    private String eventId;

    @TableField("user_id")
    private Long userId;

    @TableField("event_name")
    private String eventName;

    @TableField("target_type")
    private String targetType;

    @TableField("target_key")
    private String targetKey;

    @TableField(value = "payload", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String payload;

    @TableField("trace_id")
    private String traceId;

    @TableField("source_span_id")
    private String sourceSpanId;

    @TableField("run_id")
    private String runId;

    @TableField("interaction_id")
    private String interactionId;

    @TableField("caused_by_event_id")
    private String causedByEventId;

    @TableField("occurred_at")
    private Instant occurredAt;

    @TableField("status")
    private String status;

    @TableField("attempts")
    private Integer attempts;

    @TableField("next_attempt_at")
    private Instant nextAttemptAt;

    @TableField("last_error")
    private String lastError;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("created_at")
    private Instant createdAt;
}
