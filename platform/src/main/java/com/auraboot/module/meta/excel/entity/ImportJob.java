package com.auraboot.module.meta.excel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Entity for ab_import_job table.
 * Tracks the status and progress of Excel import operations.
 *
 * @author AuraBoot Team
 * @since 6.0.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_import_job")
public class ImportJob {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("model_code")
    private String modelCode;

    @TableField("file_name")
    private String fileName;

    /** PENDING, RUNNING, COMPLETED, FAILED */
    @TableField("status")
    private String status;

    @TableField("total_rows")
    private Integer totalRows;

    @TableField("processed_rows")
    private Integer processedRows;

    @TableField("success_rows")
    private Integer successRows;

    @TableField("error_rows")
    private Integer errorRows;

    /** INSERT, UPSERT, CHAIN */
    @TableField("import_mode")
    private String importMode;

    @TableField("error_report_url")
    private String errorReportUrl;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("created_by")
    private Long createdBy;

    @TableLogic
    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
