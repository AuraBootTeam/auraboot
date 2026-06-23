package com.auraboot.framework.behavior.outcome;

import com.auraboot.framework.behavior.mapper.BehaviorOutcomeOutboxMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BehaviorOutcomePublisher {

    private final BehaviorOutcomeOutboxMapper mapper;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean publish(BehaviorOutcomeEvent event) {
        validate(event);
        Instant now = Instant.now();
        BehaviorOutcomeOutbox row = new BehaviorOutcomeOutbox();
        row.setTenantId(event.getTenantId());
        row.setUserId(event.getUserId());
        row.setEventId(event.getEventId());
        row.setEventName(event.getEventName());
        row.setTargetType(event.getTargetType());
        row.setTargetKey(event.getTargetKey());
        row.setPayload(toPayload(event));
        row.setTraceId(event.getTraceId());
        row.setSourceSpanId(event.getSourceSpanId());
        row.setRunId(event.getRunId());
        row.setInteractionId(event.getInteractionId());
        row.setCausedByEventId(event.getCausedByEventId());
        row.setOccurredAt(event.getOccurredAt() == null ? now : event.getOccurredAt());
        row.setStatus("pending");
        row.setAttempts(0);
        row.setNextAttemptAt(now);
        row.setCreatedAt(now);
        return mapper.insertPending(row) == 1;
    }

    private String toPayload(BehaviorOutcomeEvent event) {
        try {
            return objectMapper.writeValueAsString(event.getProps() == null ? Map.of() : event.getProps());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Behavior outcome props are not JSON serializable", e);
        }
    }

    private void validate(BehaviorOutcomeEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Behavior outcome event is required");
        }
        require(event.getTenantId() != null, "tenantId is required");
        require(hasText(event.getEventId()), "eventId is required");
        require(event.getEventId().length() <= 40, "eventId must be 40 characters or fewer");
        require(hasText(event.getEventName()), "eventName is required");
        require(event.getEventName().length() <= 120, "eventName must be 120 characters or fewer");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
