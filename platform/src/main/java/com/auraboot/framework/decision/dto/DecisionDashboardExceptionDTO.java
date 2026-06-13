package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.time.Instant;

/**
 * Recent DecisionOps exception queue row.
 */
@Data
public class DecisionDashboardExceptionDTO {
    private String traceId;
    private String code;
    private String status;
    private String error;
    private Instant time;
}
