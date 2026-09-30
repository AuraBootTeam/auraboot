package com.auraboot.framework.semantic.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Alert definition as returned by the alert endpoints.
 */
@Data
@Builder
public class SemanticMetricAlertDTO {

    private String pid;
    private String name;
    private String metricPid;
    private String metricCode;
    private String metricLabel;
    private String comparator;
    private BigDecimal threshold;
    private Integer silenceMinutes;
    private String alertStatus;
    private OffsetDateTime lastTriggeredAt;
    private OffsetDateTime lastEvaluatedAt;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
