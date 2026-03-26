package com.auraboot.framework.bi.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * Report schedule entity for scheduled report email delivery.
 * Stores cron expression, recipients, format, and execution metadata.
 */
@Data
@TableName(value = "ab_report_schedule", autoResultMap = true)
public class ReportSchedule {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    /** Reference to the report page schema id or report key */
    private String reportId;

    /** Human-readable name for this schedule */
    private String name;

    /** Cron expression (e.g. "0 0 8 * * MON" for every Monday 8am) */
    private String scheduleCron;

    /** JSON array of recipient email addresses */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> recipients;

    /** Output format: PDF, EXCEL, HTML */
    private String format;

    /** Email subject template, supports ${reportName} and ${date} placeholders */
    private String subjectTemplate;

    /** Whether this schedule is active */
    private Boolean enabled;

    private Date lastRunAt;

    private Date nextRunAt;

    private String lastRunStatus;

    private String lastRunError;

    private Date createdAt;

    private Date updatedAt;

    private Long createdBy;

    private Boolean deletedFlag;
}
