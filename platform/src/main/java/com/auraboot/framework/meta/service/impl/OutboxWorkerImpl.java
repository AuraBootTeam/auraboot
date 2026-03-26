package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.entity.OutboxEvent;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Outbox polling worker.
 * Periodically polls the outbox table for pending events and dispatches them
 * via Spring ApplicationEventPublisher with exponential backoff retry.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxWorkerImpl {

    private static final int BATCH_SIZE = 50;
    private static final long BASE_DELAY_SECONDS = 1;
    private static final Duration CLEANUP_RETENTION = Duration.ofDays(7);

    private final OutboxEventMapper outboxEventMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;
    private final WebhookDispatcher webhookDispatcher;

    /**
     * Poll and dispatch pending outbox events.
     * Scheduled via DatabaseSchedulerEngine (sys-outbox-poll, interval 5s).
     */
    public void pollAndDispatch() {
        List<OutboxEvent> events = outboxEventMapper.findReadyEvents(BATCH_SIZE);
        if (events.isEmpty()) {
            return;
        }

        log.debug("Outbox worker found {} events ready for dispatch", events.size());

        for (OutboxEvent event : events) {
            int claimed = outboxEventMapper.claimEvent(event.getId());
            if (claimed == 0) {
                continue; // Already claimed by another instance
            }

            try {
                Object domainEvent = deserializeEvent(event);
                applicationEventPublisher.publishEvent(domainEvent);
                outboxEventMapper.markDelivered(event.getId());
                log.debug("Outbox event {} delivered successfully", event.getEventId());

                // Dispatch to matching webhook subscriptions
                dispatchToWebhooks(event);
            } catch (Exception e) {
                Instant nextRetry = calculateNextRetry(event.getRetryCount());
                String errorMsg = truncateError(e.getMessage());
                outboxEventMapper.incrementRetry(event.getId(), nextRetry, errorMsg);

                if (event.getRetryCount() + 1 >= event.getMaxRetries()) {
                    log.error("Outbox event {} exceeded max retries, marked as FAILED. " +
                            "EventType={}, CommandCode={}", event.getEventId(),
                            event.getEventType(), event.getCommandCode(), e);
                } else {
                    log.warn("Outbox event {} dispatch failed (retry {}), next retry at {}",
                            event.getEventId(), event.getRetryCount() + 1, nextRetry);
                }
            }
        }
    }

    /**
     * Cleanup delivered events older than retention period.
     * Scheduled via DatabaseSchedulerEngine (sys-outbox-cleanup, interval 1h).
     */
    public void cleanupDelivered() {
        Instant before = Instant.now().minus(CLEANUP_RETENTION);
        int deleted = outboxEventMapper.cleanupDelivered(before);
        if (deleted > 0) {
            log.info("Outbox cleanup: removed {} delivered events older than {} days",
                    deleted, CLEANUP_RETENTION.toDays());
        }
    }

    private Object deserializeEvent(OutboxEvent outboxEvent) {
        try {
            // Deserialize using the eventType as class discriminator
            String className = resolveEventClassName(outboxEvent.getEventType());
            Class<?> eventClass = Class.forName(className);
            return objectMapper.readValue(outboxEvent.getPayload(), eventClass);
        } catch (ClassNotFoundException e) {
            throw new BusinessException("Unknown event type: " + outboxEvent.getEventType(), e);
        } catch (Exception e) {
            throw new BusinessException("Failed to deserialize outbox event: " + outboxEvent.getEventId(), e);
        }
    }

    private String resolveEventClassName(String eventType) {
        // If already a fully qualified class name, use as-is
        if (eventType.contains(".")) {
            return eventType;
        }
        // Default package for command events
        return "com.auraboot.framework.meta.event." + eventType;
    }

    /**
     * Calculate next retry time with exponential backoff.
     * baseDelay * 2^retryCount: 1s, 2s, 4s, 8s, ..., 512s (~8.5min)
     */
    private Instant calculateNextRetry(int retryCount) {
        long delaySeconds = BASE_DELAY_SECONDS * (1L << retryCount);
        return Instant.now().plusSeconds(delaySeconds);
    }

    private String truncateError(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /**
     * Dispatch outbox event to matching webhook subscriptions.
     * Non-blocking: failures are logged but do not affect event delivery status.
     */
    private void dispatchToWebhooks(OutboxEvent event) {
        try {
            String eventType = event.getEventType();
            Map<String, Object> payload = parsePayloadAsMap(event.getPayload());
            if (payload == null) {
                payload = Map.of("eventId", event.getEventId(), "eventType", eventType);
            }
            payload = new java.util.HashMap<>(payload);
            payload.put("_eventId", event.getEventId());
            payload.put("_eventType", eventType);
            payload.put("_commandCode", event.getCommandCode());

            webhookDispatcher.dispatch(eventType, payload, event.getTenantId());
        } catch (Exception e) {
            log.debug("Webhook dispatch skipped for event {}: {}", event.getEventId(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayloadAsMap(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
