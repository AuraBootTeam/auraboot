package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Event Snapshot entity.
 * Stores serialized aggregate state at a point-in-time for replay optimization.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_event_snapshot")
public class EventSnapshot {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("aggregate_type")
    private String aggregateType;

    @TableField("aggregate_id")
    private String aggregateId;

    @TableField("version")
    private Integer version;

    @TableField(value = "state", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String state;

    @TableField(value = "metadata", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String metadata;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
