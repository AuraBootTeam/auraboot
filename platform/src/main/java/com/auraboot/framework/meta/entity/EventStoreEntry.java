package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Event Store entry entity.
 * Represents a single domain event persisted for event sourcing.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_event_store")
public class EventStoreEntry {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("event_id")
    private String eventId;

    @TableField("event_type")
    private String eventType;

    @TableField("aggregate_type")
    private String aggregateType;

    @TableField("aggregate_id")
    private String aggregateId;

    @TableField("version")
    private Integer version;

    @TableField(value = "payload", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String payload;

    @TableField(value = "metadata", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String metadata;

    @TableField("occurred_at")
    private Instant occurredAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
