package com.auraboot.framework.integration;

import com.auraboot.framework.meta.constant.OutboxStatus;
import com.auraboot.framework.meta.entity.OutboxEvent;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

import java.time.Instant;
import java.util.Locale;

/** Platform-owned transactional outbox bridge exposed to plugins by type. */
@Service
public class ReliableIntegrationAccessorImpl implements ReliableIntegrationAccessor {

    private static final int DEFAULT_MAX_RETRIES = 10;

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;
    private final ReliableIntegrationMetrics metrics;
    private final InventoryReceiptEnqueueProvenance provenance;

    public ReliableIntegrationAccessorImpl(OutboxEventMapper outboxEventMapper, ObjectMapper objectMapper,
                                           ReliableIntegrationMetrics metrics) {
        this(outboxEventMapper, objectMapper, metrics, null);
    }
    @Autowired
    public ReliableIntegrationAccessorImpl(OutboxEventMapper outboxEventMapper, ObjectMapper objectMapper,
                                           ReliableIntegrationMetrics metrics, InventoryReceiptEnqueueProvenance provenance) {
        this.outboxEventMapper = outboxEventMapper; this.objectMapper = objectMapper;
        this.metrics = metrics; this.provenance = provenance;
    }


    @Override
    public void enqueue(IntegrationEventEnvelope envelope) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Reliable integration events require an active business transaction");
        }
        envelope = prepareProvenance(envelope);
        OutboxEvent event = toEvent(envelope);
        outboxEventMapper.insertEnvelope(event);
        recordEnqueuedAfterCommit();
    }

    @Override
    public boolean enqueueIdempotent(IntegrationEventEnvelope envelope) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Reliable integration events require an active business transaction");
        }
        envelope = prepareProvenance(envelope);
        OutboxEvent event = toEvent(envelope);
        int inserted = outboxEventMapper.insertEnvelopeIfAbsent(event);
        if (inserted == 1) {
            recordEnqueuedAfterCommit();
            return true;
        }
        if (inserted != 0 || outboxEventMapper.countMatchingEnvelope(event) != 1) {
            throw new IllegalArgumentException("RELIABLE_ENVELOPE_CONFLICT");
        }
        return false;
    }

    private IntegrationEventEnvelope prepareProvenance(IntegrationEventEnvelope envelope) {
        InventoryReceiptEnqueueProvenance.rejectReservedHeaders(envelope);
        if (!InventoryReceiptEnqueueProvenance.EVENT.equals(envelope.eventType())) return envelope;
        if (provenance == null) throw new SecurityException("Inventory posted receipt host provenance is unavailable");
        return provenance.stamp(envelope);
    }

    private OutboxEvent toEvent(IntegrationEventEnvelope envelope) {
        OutboxEvent event = new OutboxEvent();
        event.setTenantId(envelope.tenantId());
        event.setEventId(envelope.eventId());
        event.setEventType(envelope.eventType());
        if (InventoryReceiptEnqueueProvenance.EVENT.equals(envelope.eventType()))
            event.setCommandCode(InventoryReceiptEnqueueProvenance.stampedProducerCommand(envelope));
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
        return event;
    }

    private void recordEnqueuedAfterCommit() {
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
