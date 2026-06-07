package com.auraboot.framework.scheduler.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Scheduled task definition entity.
 *
 * @since 5.1.0
 */
@Data
@TableName(value = "ab_scheduled_task", autoResultMap = true)
public class ScheduledTask {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("pid")
    private String pid;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    /**
     * Task type: CRON / INTERVAL / ONE_TIME.
     */
    @TableField("task_type")
    private String taskType;

    @TableField("cron_expression")
    private String cronExpression;

    /**
     * Optional IANA timezone ID for CRON tasks (e.g. "Asia/Shanghai", "America/New_York").
     * When null, falls back to the tenant's configured timezone, then UTC.
     */
    @TableField("timezone")
    private String timezone;

    @TableField("interval_ms")
    private Long intervalMs;

    @TableField("handler_bean")
    private String handlerBean;

    @TableField("handler_method")
    private String handlerMethod;

    @TableField(value = "params", typeHandler = JsonbStringTypeHandler.class)
    private String params;

    @TableField("max_retries")
    private Integer maxRetries;

    @TableField("timeout_ms")
    private Long timeoutMs;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("scheduler_type")
    private String schedulerType;

    @TableField("external_job_id")
    private String externalJobId;

    @TableField("external_executor_app")
    private String externalExecutorApp;

    @TableField("external_sync_status")
    private String externalSyncStatus;

    @TableField("external_sync_error")
    private String externalSyncError;

    @TableField("route_strategy")
    private String routeStrategy;

    @TableField("block_strategy")
    private String blockStrategy;

    @TableField("misfire_strategy")
    private String misfireStrategy;

    @TableField("sharding_enabled")
    private Boolean shardingEnabled;

    @TableField("last_run_at")
    private Instant lastRunAt;

    @TableField("next_run_at")
    private Instant nextRunAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
