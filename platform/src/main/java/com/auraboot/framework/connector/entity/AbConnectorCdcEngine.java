package com.auraboot.framework.connector.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * CDC engine lease + position record (PRD 18 §B.3.3 — {@code ab_connector_cdc_engine}).
 *
 * <p>One row per (connector_pid). For multi-worker deployments, {@code workerNode} is
 * the single-instance lease holder; non-holders observe but do not write.
 *
 * @since 5.3.0
 */
@Data
@TableName(value = "ab_connector_cdc_engine", autoResultMap = true)
public class AbConnectorCdcEngine {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("connector_pid")
    private String connectorPid;

    /** IDLE / RUNNING / PAUSED / FAILED. */
    @TableField("status")
    private String status;

    @TableField(value = "last_position", typeHandler = JsonbStringTypeHandler.class)
    private String lastPosition;

    @TableField("last_event_at")
    private Instant lastEventAt;

    @TableField("worker_node")
    private String workerNode;

    @TableField("heartbeat_at")
    private Instant heartbeatAt;

    @TableField(value = "meta", typeHandler = JsonbStringTypeHandler.class)
    private String meta;
}
