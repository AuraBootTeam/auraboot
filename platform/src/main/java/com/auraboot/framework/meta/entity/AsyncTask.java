package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

/**
 * Unified async task entity.
 * Tracks background jobs like exports, batch operations, MRP calculations, etc.
 */
@Data
@TableName(value = "ab_async_task", autoResultMap = true)
public class AsyncTask {

    // Status constants
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    // Task type constants
    public static final String TYPE_EXPORT = "export";
    public static final String TYPE_IMPORT = "import";
    public static final String TYPE_MRP_CALC = "mrp_calc";
    public static final String TYPE_REPORT_GEN = "report_gen";
    public static final String TYPE_BATCH_OP = "batch_op";
    public static final String TYPE_CUSTOM = "custom";
    public static final String TYPE_PLUGIN_IMPORT = "plugin_import";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("task_code")
    private String taskCode;

    @TableField("task_type")
    private String taskType;

    @TableField("task_name")
    private String taskName;

    @TableField("status")
    private String status;

    @TableField("priority")
    private Integer priority;

    @TableField("progress")
    private Integer progress;

    @TableField("progress_message")
    private String progressMessage;

    @TableField(value = "input_params", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode inputParams;

    @TableField(value = "result_data", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode resultData;

    @TableField("error_message")
    private String errorMessage;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("max_retries")
    private Integer maxRetries;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("started_at")
    private Instant startedAt;

    @TableField("completed_at")
    private Instant completedAt;

    @TableField("cancelled_at")
    private Instant cancelledAt;

    @TableField("timeout_seconds")
    private Integer timeoutSeconds;

    /**
     * Check if the task is in a terminal state.
     */
    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status)
                || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }

    /**
     * Check if the task can be cancelled.
     */
    public boolean isCancellable() {
        return STATUS_PENDING.equals(status) || STATUS_RUNNING.equals(status);
    }
}
