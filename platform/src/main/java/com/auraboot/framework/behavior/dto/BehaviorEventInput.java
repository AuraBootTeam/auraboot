package com.auraboot.framework.behavior.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * One incoming behavior event from the browser/SDK (M1; SoT §5.5 envelope, client side).
 * tenant/user are NOT trusted from the client — the server enriches them from the auth
 * context. {@code eventId} is a client ULID generated once (retries reuse it → idempotent).
 */
@Data
public class BehaviorEventInput {
    private String eventId;
    private String schemaVersion;
    private String eventName;
    private String eventCategory;
    private String source;
    private String identityQuality;
    private Instant occurredAt;
    private String anonId;
    private String clientSessionId;
    private String interactionId;
    private String causedByEventId;
    private String traceId;
    private String sourceSpanId;
    private String runId;
    private String uiElementId;
    private String appId;
    private String pageId;
    private String blockId;
    private String elementCode;
    private Map<String, Object> props;
    private String consentState;
    private String consentVersion;
    private String samplingUnit;
    private BigDecimal samplingProbability;
    private String producerName;
    private String producerVersion;
}
