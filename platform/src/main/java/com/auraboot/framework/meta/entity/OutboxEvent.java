package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Outbox event entity for reliable event delivery.
 * Events are written to the outbox table within the same transaction as business data,
 * then dispatched by a background worker.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_outbox")
public class OutboxEvent {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("event_id")
    private String eventId;

    @TableField("event_type")
    private String eventType;

    @TableField("command_code")
    private String commandCode;

    @TableField("schema_version")
    private String schemaVersion;

    @TableField("event_source")
    private String eventSource;

    @TableField("subject")
    private String subject;

    @TableField("occurred_at")
    private Instant occurredAt;

    @TableField("correlation_id")
    private String correlationId;

    @TableField("causation_id")
    private String causationId;

    @TableField("ordering_key")
    private String orderingKey;

    @TableField("event_sequence")
    private Long eventSequence;

    @TableField(value = "headers", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String headers;

    @TableField(value = "payload", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String payload;

    @TableField("status")
    private String status;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("max_retries")
    private Integer maxRetries;

    @TableField("next_retry_at")
    private Instant nextRetryAt;

    @TableField("last_error")
    private String lastError;

    @TableField("lease_owner")
    private String leaseOwner;

    @TableField("lease_token")
    private String leaseToken;

    @TableField("lease_until")
    private Instant leaseUntil;

    @TableField("claimed_at")
    private Instant claimedAt;

    @TableField("replay_count")
    private Integer replayCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField("delivered_at")
    private Instant deliveredAt;
}
