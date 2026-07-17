package com.auraboot.framework.eventpolicy.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * EventPolicy action execution evidence linked from Decision Runtime logs.
 */
@Data
public class EventPolicyActionLogDTO {
    private String pid;
    private Long tenantId;
    private String idempotencyKey;
    private String policyCode;
    private String decisionTraceId;
    private String correlationId;
    private String ruleCode;
    private String actionType;
    private String status;
    private String failureStrategy;
    private String errorMessage;
    private Map<String, Object> resultPayload;
    private Map<String, Object> actionPayload;
    private Map<String, Object> contextPayload;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Instant nextRetryAt;
    private Instant lastRetryAt;
    private Instant deadLetteredAt;
    private Instant executedAt;
}
