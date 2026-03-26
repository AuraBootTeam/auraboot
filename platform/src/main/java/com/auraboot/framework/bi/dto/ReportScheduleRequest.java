package com.auraboot.framework.bi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Request DTO for creating/updating a report schedule.
 */
@Data
public class ReportScheduleRequest {

    /** Human-readable name */
    @NotBlank(message = "name is required")
    private String name;

    /** Reference to the report (page schema ID or report key) */
    @NotBlank(message = "reportId is required")
    private String reportId;

    /** Cron expression */
    @NotBlank(message = "scheduleCron is required")
    private String scheduleCron;

    /** List of recipient email addresses */
    @NotEmpty(message = "At least one recipient is required")
    private List<String> recipients;

    /** Output format: PDF, EXCEL, HTML */
    private String format = "pdf";

    /** Email subject template */
    private String subjectTemplate;

    /** Whether this schedule is active */
    private Boolean enabled = true;
}
