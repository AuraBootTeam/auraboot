package com.auraboot.framework.scheduler.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Scheduled task execution log entity.
 *
 * @since 5.1.0
 */
@Data
@TableName("ab_scheduled_task_log")
public class ScheduledTaskLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("task_pid")
    private String taskPid;

    /**
     * Status: RUNNING / SUCCESS / FAILED / TIMEOUT.
     */
    @TableField("status")
    private String status;

    @TableField("started_at")
    private Instant startedAt;

    @TableField("finished_at")
    private Instant finishedAt;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField("result")
    private String result;

    @TableField("error_message")
    private String errorMessage;

    @TableField("retry_count")
    private Integer retryCount;

    /**
     * Trigger type: SCHEDULED / MANUAL.
     */
    @TableField("trigger_type")
    private String triggerType;
}
