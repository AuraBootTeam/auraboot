package com.auraboot.framework.integration;

import com.auraboot.framework.meta.constant.OutboxStatus;
import com.auraboot.framework.meta.entity.OutboxEvent;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

import java.time.Instant;
import java.util.Locale;

/** Platform-owned transactional outbox bridge exposed to plugins by type. */
@Service
@RequiredArgsConstructor
public class ReliableIntegrationAccessorImpl implements ReliableIntegrationAccessor {

    private static final int DEFAULT_MAX_RETRIES = 10;

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;
    private final ReliableIntegrationMetrics metrics;

    @Override
    public void enqueue(IntegrationEventEnvelope envelope) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Reliable integration events require an active business transaction");
        }
        OutboxEvent event = new OutboxEvent();
        event.setTenantId(envelope.tenantId());
        event.setEventId(envelope.eventId());
        event.setEventType(envelope.eventType());
        event.setSchemaVersion(envelope.schemaVersion());
        event.setEventSource(envelope.source());
        event.setSubject(envelope.subject());
        event.setOccurredAt(envelope.occurredAt());
        event.setCorrelationId(envelope.correlationId());
        event.setCausationId(envelope.causationId());
        event.setOrderingKey(envelope.orderingKey());
        event.setEventSequence(envelope.sequence());
        event.setPayload(json(envelope.payload()));
        event.setHeaders(json(envelope.headers()));
        event.setStatus(OutboxStatus.PENDING.name().toLowerCase(Locale.ROOT));
        event.setRetryCount(0);
        event.setMaxRetries(DEFAULT_MAX_RETRIES);
        event.setNextRetryAt(Instant.now());
        event.setCreatedAt(Instant.now());
        event.setReplayCount(0);
        outboxEventMapper.insertEnvelope(event);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.record("enqueued");
            }
        });
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Reliable integration envelope is not JSON serializable", e);
        }
    }
}
