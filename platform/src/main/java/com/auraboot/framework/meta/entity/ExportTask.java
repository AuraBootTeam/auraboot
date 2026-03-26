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
 * Async export task entity.
 * Tracks progress of large data exports from named queries.
 */
@Data
@TableName(value = "ab_export_task", autoResultMap = true)
public class ExportTask {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_EXPIRED = "expired";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("query_code")
    private String queryCode;

    @TableField("status")
    private String status;

    @TableField("progress")
    private Integer progress;

    @TableField("total_rows")
    private Long totalRows;

    @TableField("processed_rows")
    private Long processedRows;

    @TableField("file_key")
    private String fileKey;

    @TableField("file_size")
    private Long fileSize;

    @TableField("format")
    private String format;

    @TableField(value = "request_params", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode requestParams;

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("completed_at")
    private Instant completedAt;

    @TableField("expires_at")
    private Instant expiresAt;

    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status) || STATUS_EXPIRED.equals(status);
    }
}
