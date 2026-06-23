package com.auraboot.framework.behavior.outcome;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

/**
 * Server-side business outcome that must be written to the local transactional
 * outbox before it is relayed into the behavior ingest topic.
 */
@Value
@Builder
public class BehaviorOutcomeEvent {
    Long tenantId;
    Long userId;
    String eventId;
    String eventName;
    Instant occurredAt;
    String targetType;
    String targetKey;
    String traceId;
    String sourceSpanId;
    String runId;
    String interactionId;
    String causedByEventId;
    Map<String, Object> props;
}
