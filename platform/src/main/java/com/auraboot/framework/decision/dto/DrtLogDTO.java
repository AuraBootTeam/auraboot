package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

/**
 * DTO for an evaluation log entry.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class DrtLogDTO {
    private Long id;
    private String pid;
    private Long tenantId;
    private String traceId;
    private String correlationId;
    private String decisionCode;
    /** Current tenant catalogue name; canonical code remains available separately. */
    private String decisionName;
    private Integer decisionVersion;
    private Integer selectedVersion;
    private String rolloutPolicyPid;
    private Integer rolloutBucket;
    private String rolloutArm;
    private String routingKey;
    private String rolloutResultKey;
    private String kind;
    private String runtimeAdapter;
    private String callerType;
    private String callerRef;
    private String inputDigest;
    private String resultDigest;
    private Boolean matched;
    private String status;
    private JsonNode matchedRulesJson;
    private JsonNode outputSnapshot;
    private JsonNode traceSnapshot;
    private Long durationMs;
    private String errorCode;
    private String errorMessage;
    private Instant createdAt;
}
