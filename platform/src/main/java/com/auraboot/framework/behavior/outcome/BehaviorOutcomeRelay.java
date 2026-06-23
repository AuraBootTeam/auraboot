package com.auraboot.framework.behavior.outcome;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.ingest.BehaviorIngestPublisher;
import com.auraboot.framework.behavior.mapper.BehaviorOutcomeOutboxMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorOutcomeRelay {

    private static final int DEFAULT_LIMIT = 50;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final BehaviorOutcomeOutboxMapper mapper;
    private final BehaviorIngestPublisher ingestPublisher;
    private final ObjectMapper objectMapper;

    public int publishPending() {
        return publishPending(DEFAULT_LIMIT);
    }

    public int publishPending(int limit) {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, DEFAULT_LIMIT);
        int published = 0;
        for (BehaviorOutcomeOutbox row : mapper.findPending(safeLimit)) {
            if (mapper.claimPending(row.getId()) == 0) {
                continue;
            }
            try {
                ingestPublisher.publish(row.getTenantId(), row.getUserId(), List.of(toBehaviorEvent(row)));
                mapper.markPublished(row.getId());
                published += 1;
            } catch (RuntimeException e) {
                mapper.markFailed(row.getId(), nextAttempt(row.getAttempts()), truncate(e));
                log.warn("Behavior outcome outbox relay failed for event {}: {}",
                        row.getEventId(), e.getMessage());
            }
        }
        return published;
    }

    private BehaviorEventInput toBehaviorEvent(BehaviorOutcomeOutbox row) {
        BehaviorEventInput event = new BehaviorEventInput();
        event.setEventId(row.getEventId());
        event.setSchemaVersion("1");
        event.setEventName(row.getEventName());
        event.setEventCategory("business_outcome");
        event.setSource("server");
        event.setIdentityQuality("declared");
        event.setOccurredAt(row.getOccurredAt());
        event.setInteractionId(row.getInteractionId());
        event.setCausedByEventId(row.getCausedByEventId());
        event.setTraceId(row.getTraceId());
        event.setSourceSpanId(row.getSourceSpanId());
        event.setRunId(row.getRunId());
        event.setProducerName("server-outcome-outbox");
        event.setProducerVersion("1.0.0");
        event.setSamplingUnit("event");
        event.setSamplingProbability(BigDecimal.ONE);
        event.setProps(props(row));
        return event;
    }

    private Map<String, Object> props(BehaviorOutcomeOutbox row) {
        Map<String, Object> props = new LinkedHashMap<>(parsePayload(row.getPayload()));
        if (row.getTargetType() != null) {
            props.putIfAbsent("targetType", row.getTargetType());
        }
        if (row.getTargetKey() != null) {
            props.putIfAbsent("targetKey", row.getTargetKey());
        }
        return props;
    }

    private Map<String, Object> parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse behavior outcome payload", e);
        }
    }

    private Instant nextAttempt(Integer attempts) {
        int current = attempts == null ? 0 : attempts;
        long delaySeconds = Math.min(300L, 1L << Math.min(current, 8));
        return Instant.now().plusSeconds(delaySeconds);
    }

    private String truncate(RuntimeException e) {
        String value = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
