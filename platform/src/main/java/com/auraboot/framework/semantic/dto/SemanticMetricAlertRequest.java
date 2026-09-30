package com.auraboot.framework.semantic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Create/update payload for a threshold alert over a published semantic metric.
 */
@Data
public class SemanticMetricAlertRequest {

    @NotBlank
    private String name;

    /** pid of the published semantic metric to watch. */
    @NotBlank
    private String metricPid;

    /** gt | gte | lt | lte */
    @NotBlank
    private String comparator;

    @NotNull
    private BigDecimal threshold;

    /** Minutes a triggered alert stays quiet before re-notifying. */
    private Integer silenceMinutes = 60;

    /** active | paused (defaults to active on create). */
    private String alertStatus;
}
