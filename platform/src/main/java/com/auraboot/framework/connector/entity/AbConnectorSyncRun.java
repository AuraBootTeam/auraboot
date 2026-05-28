package com.auraboot.framework.connector.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Connector sync run history (PRD 18 §B.3.3 — {@code ab_connector_sync_run}).
 *
 * <p>One row per execution of any {@link com.auraboot.framework.connector.cdc.SyncStrategy}.
 *
 * @since 5.3.0
 */
@Data
@TableName(value = "ab_connector_sync_run", autoResultMap = true)
public class AbConnectorSyncRun {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("connector_pid")
    private String connectorPid;

    /** SCHEDULED / MANUAL / CDC / WEBHOOK. */
    @TableField("trigger_type")
    private String triggerType;

    @TableField(value = "started_at", fill = FieldFill.INSERT)
    private Instant startedAt;

    @TableField("finished_at")
    private Instant finishedAt;

    /** RUNNING / SUCCESS / FAILED / PARTIAL. */
    @TableField("status")
    private String status;

    @TableField("records_read")
    private Integer recordsRead;

    @TableField("records_written")
    private Integer recordsWritten;

    @TableField("records_failed")
    private Integer recordsFailed;

    @TableField("error_message")
    private String errorMessage;

    @TableField(value = "cursor_state", typeHandler = JsonbStringTypeHandler.class)
    private String cursorState;

    @TableField("duration_ms")
    private Integer durationMs;
}
