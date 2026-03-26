package com.auraboot.framework.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for creating/updating a scheduled task.
 *
 * @since 5.1.0
 */
@Data
public class ScheduledTaskCreateRequest {

    @NotBlank
    private String name;

    private String description;

    /**
     * CRON / INTERVAL / ONE_TIME.
     */
    @NotBlank
    private String taskType;

    private String cronExpression;

    /**
     * Optional IANA timezone ID for CRON tasks (e.g. "Asia/Shanghai", "America/New_York").
     * When null, the engine falls back to the tenant's configured timezone, then UTC.
     */
    private String timezone;

    private Long intervalMs;

    @NotBlank
    private String handlerBean;

    private String handlerMethod = "execute";
    private String params;
    private Integer maxRetries = 0;
    private Long timeoutMs = 300000L;
    private boolean enabled = true;
}
