package com.auraboot.framework.bi.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * Response DTO for report schedule.
 */
@Data
public class ReportScheduleResponse {

    private Long id;
    private String pid;
    private String name;
    private String reportId;
    private String scheduleCron;
    private List<String> recipients;
    private String format;
    private String subjectTemplate;
    private Boolean enabled;
    private Date lastRunAt;
    private Date nextRunAt;
    private String lastRunStatus;
    private String lastRunError;
    private Date createdAt;
    private Date updatedAt;
    private Long createdBy;
}
