package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.IntegrationBackoffPolicy;
import com.auraboot.framework.integration.ReliableEventConsumerRegistry;
import com.auraboot.framework.integration.ReliableEventDeliveryExecutor;
import com.auraboot.framework.integration.ReliableIntegrationMetrics;
import com.auraboot.framework.integration.ReliableIntegrationStateService;
import com.auraboot.framework.meta.entity.OutboxEvent;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
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
import java.util.UUID;

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
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration CLEANUP_RETENTION = Duration.ofDays(7);
    private static final IntegrationBackoffPolicy BACKOFF =
            new IntegrationBackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(15));
    private static final String LEASE_OWNER =
            java.lang.management.ManagementFactory.getRuntimeMXBean().getName();

    private final OutboxEventMapper outboxEventMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;
    private final WebhookDispatcher webhookDispatcher;
    private final ReliableEventConsumerRegistry consumerRegistry;
    private final ReliableEventDeliveryExecutor deliveryExecutor;
    private final ReliableIntegrationStateService stateService;
    private final ReliableIntegrationMetrics metrics;

    /**
     * Poll and dispatch pending outbox events.
     * Scheduled via DatabaseSchedulerEngine (sys-outbox-poll, interval 5s).
     */
    public void pollAndDispatch() {
        String leaseToken = UUID.randomUUID().toString();
        List<OutboxEvent> events = outboxEventMapper.claimReadyEvents(
                BATCH_SIZE, LEASE_OWNER, leaseToken, Instant.now().plus(LEASE_DURATION));
        if (events.isEmpty()) {
            return;
        }

        log.debug("Outbox worker found {} events ready for dispatch", events.size());

        for (OutboxEvent event : events) {
            metrics.record("claimed");
            try {
                if (event.getSchemaVersion() == null) {
                    dispatchLegacy(event);
                } else {
                    dispatchReliable(event, leaseToken);
                }
                if (!stateService.markDelivered(event, leaseToken)) {
                    log.warn("Outbox event {} finished after its lease fence was lost", event.getEventId());
                }
            } catch (Exception e) {
                Instant nextRetry = Instant.now().plus(BACKOFF.delayForRetry(event.getRetryCount()));
                String errorMsg = truncateError(e.getMessage());
                stateService.recordFailure(event, leaseToken, nextRetry, errorMsg);

                if (event.getRetryCount() + 1 >= event.getMaxRetries()) {
                    log.error("Outbox event {} exceeded max retries and entered the DLQ. " +
                            "EventType={}, CommandCode={}", event.getEventId(),
                            event.getEventType(), event.getCommandCode(), e);
                } else {
                    log.warn("Outbox event {} dispatch failed (retry {}), next retry at {}",
                            event.getEventId(), event.getRetryCount() + 1, nextRetry);
                }
            }
        }
    }

    private void dispatchReliable(OutboxEvent event, String leaseToken) {
        IntegrationEventEnvelope envelope = toEnvelope(event);
        List<ReliableEventConsumerExtension> consumers = consumerRegistry.consumersFor(event.getEventType());
        if (consumers.isEmpty()) {
            throw new BusinessException("No reliable consumer registered for event type: " + event.getEventType());
        }
        for (ReliableEventConsumerExtension consumer : consumers) {
            deliveryExecutor.deliver(envelope, consumer, leaseToken);
        }
    }

    private void dispatchLegacy(OutboxEvent event) {
        Object domainEvent = deserializeEvent(event);
        applicationEventPublisher.publishEvent(domainEvent);
        dispatchToWebhooks(event);
    }

    private IntegrationEventEnvelope toEnvelope(OutboxEvent event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    event.getPayload(), new TypeReference<Map<String, Object>>() {});
            Map<String, String> headers = event.getHeaders() == null
                    ? Map.of()
                    : objectMapper.readValue(event.getHeaders(), new TypeReference<Map<String, String>>() {});
            return new IntegrationEventEnvelope(
                    event.getSchemaVersion(), event.getEventId(), event.getEventType(),
                    event.getEventSource(), event.getSubject(), event.getOccurredAt(),
                    event.getTenantId(), event.getCorrelationId(), event.getCausationId(),
                    event.getOrderingKey(), event.getEventSequence(), payload, headers);
        } catch (Exception e) {
            throw new BusinessException("Invalid reliable integration envelope: " + event.getEventId(), e);
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

    /** Recover expired leases and materialize any missing terminal DLQ rows. */
    public ReliableIntegrationStateService.ReconcileResult reconcile() {
        return stateService.reconcile(Instant.now());
    }

    /** Explicit operator-owned replay; never called automatically for poison events. */
    public boolean replay(long outboxId, String replayedBy) {
        if (replayedBy == null || replayedBy.isBlank()) {
            throw new IllegalArgumentException("replayedBy must not be blank");
        }
        return stateService.replay(outboxId, replayedBy);
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
