package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.IntegrationBackoffPolicy;
import com.auraboot.framework.integration.ReliableEventConsumerRegistry;
import com.auraboot.framework.integration.ReliableEventDeliveryExecutor;
import com.auraboot.framework.integration.ReliableIntegrationMetrics;
import com.auraboot.framework.integration.ReliableIntegrationClaimService;
import com.auraboot.framework.integration.ReliableIntegrationStateService;
import com.auraboot.framework.meta.entity.OutboxEvent;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationDispatcher;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.ArrayList;
import java.util.Set;
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
public class OutboxWorkerImpl implements ReliableIntegrationDispatcher {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_BATCHES_PER_POLL = 8;
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
    private final ReliableIntegrationClaimService claims;

    /**
     * Poll and dispatch pending outbox events.
     * Scheduled via DatabaseSchedulerEngine (sys-outbox-poll, interval 5s).
     */
    public void pollAndDispatch() {
        // Bound scheduler work; each batch retains its own original lease fence.
        for (int batch = 0; batch < MAX_BATCHES_PER_POLL; batch++) {
            if (dispatchReadyBatch() < BATCH_SIZE) return;
        }
    }

    @Override
    public DispatchResult dispatchCurrentTenant(String source, Set<String> eventTypes, int limit) {
        if (!MetaContext.exists()) {
            throw new SecurityException("Reliable dispatch requires a verified current tenant member");
        }
        Long tenant = MetaContext.getCurrentTenantId();
        if (tenant == null || tenant <= 0 || MetaContext.getCurrentMemberId() == null
                || MetaContext.getCurrentUserPid() == null || MetaContext.getCurrentUserPid().isBlank()) {
            throw new SecurityException("Reliable dispatch requires a verified current tenant member");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Reliable dispatch must run outside a business transaction");
        }
        requireContractCode(source, "source");
        if (limit < 1 || limit > 100 || eventTypes == null || eventTypes.isEmpty() || eventTypes.size() > 32) {
            throw new IllegalArgumentException("Reliable dispatch requires bounded event types and batch size");
        }
        for (String type : eventTypes) {
            requireContractCode(type, "eventType");
            if (consumerRegistry.consumersFor(type).isEmpty()) {
                throw new IllegalArgumentException("No reliable consumer registered for event type: " + type);
            }
        }
        String token = UUID.randomUUID().toString();
        List<OutboxEvent> events = claims.claimTenant(tenant, source, eventTypes.stream().sorted().toList(),
                limit, LEASE_OWNER, token, Instant.now().plus(LEASE_DURATION));
        return dispatchClaimed(events, token);
    }

    private void requireContractCode(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128 || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be a canonical contract code");
        }
    }

    private int dispatchReadyBatch() {
        String token = UUID.randomUUID().toString();
        // Bypass only the global outbox-plane claim, never downstream consumer business queries.
        List<OutboxEvent> events = MetaContext.runWithoutTenantFilter(() -> claims.claimGlobal(
                BATCH_SIZE, LEASE_OWNER, token, Instant.now().plus(LEASE_DURATION)));
        return dispatchClaimed(events, token).claimed();
    }

    private DispatchResult dispatchClaimed(List<OutboxEvent> events, String leaseToken) {
        int delivered = 0;
        int failed = 0;
        int leaseLost = 0;
        List<String> dead = new ArrayList<>();
        for (OutboxEvent event : events) {
            metrics.record("claimed");
            if ("failed".equals(event.getStatus())) {
                // The claim transaction atomically exhausted a crashed worker's budget and wrote its DLQ.
                failed++;
                dead.add(event.getEventId());
                metrics.record("dead_lettered");
                continue;
            }
            try {
                if (event.getSchemaVersion() == null) dispatchLegacy(event);
                else dispatchReliable(event, leaseToken);
                if (MetaContext.runWithoutTenantFilter(() -> stateService.markDelivered(event, leaseToken))) {
                    delivered++;
                } else {
                    leaseLost++;
                    log.warn("Outbox event {} finished after its lease fence was lost", event.getEventId());
                }
            } catch (Exception error) {
                Instant nextRetry = Instant.now().plus(BACKOFF.delayForRetry(event.getRetryCount()));
                String message = truncateError(error.getMessage());
                boolean changed = MetaContext.runWithoutTenantFilter(
                        () -> stateService.recordFailure(event, leaseToken, nextRetry, message));
                if (!changed) {
                    leaseLost++;
                    continue;
                }
                failed++;
                if (event.getRetryCount() + 1 >= event.getMaxRetries()) {
                    dead.add(event.getEventId());
                    log.error("Outbox event {} entered the DLQ. EventType={}, CommandCode={}",
                            event.getEventId(), event.getEventType(), event.getCommandCode(), error);
                } else {
                    log.warn("Outbox event {} dispatch failed (retry {}), next retry at {}",
                            event.getEventId(), event.getRetryCount() + 1, nextRetry);
                }
            }
        }
        return new DispatchResult(events.size(), delivered, failed, leaseLost, dead);
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
            var reader = objectMapper.readerFor(new TypeReference<Map<String, Object>>() {})
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            Map<String, Object> payload = reader.readValue(event.getPayload());
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
        int deleted = MetaContext.runWithoutTenantFilter(() -> outboxEventMapper.cleanupDelivered(before));
        if (deleted > 0) {
            log.info("Outbox cleanup: removed {} delivered events older than {} days",
                    deleted, CLEANUP_RETENTION.toDays());
        }
    }

    /** Recover expired leases and materialize any missing terminal DLQ rows. */
    public ReliableIntegrationStateService.ReconcileResult reconcile() {
        return MetaContext.runWithoutTenantFilter(() -> stateService.reconcile(Instant.now()));
    }

    /** Explicit operator-owned replay; never called automatically for poison events. */
    public boolean replay(long outboxId, String replayedBy) {
        if (replayedBy == null || replayedBy.isBlank()) {
            throw new IllegalArgumentException("replayedBy must not be blank");
        }
        return MetaContext.runWithoutTenantFilter(() -> stateService.replay(outboxId, replayedBy));
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
