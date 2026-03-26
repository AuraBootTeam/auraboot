package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.constant.OutboxStatus;
import com.auraboot.framework.meta.entity.OutboxEvent;
import com.auraboot.framework.meta.event.CommandExecutedEvent;
import com.auraboot.framework.meta.event.DecisionEvent;
import com.auraboot.framework.meta.event.StateTransitionEvent;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.meta.service.OutboxWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Outbox writer implementation.
 * Serializes events and inserts into ab_outbox table.
 * Inherits transaction from caller (no @Transactional on its own).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxWriterImpl implements OutboxWriter {

    private static final int DEFAULT_MAX_RETRIES = 10;

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void write(Object event, String commandCode, Long tenantId) {
        write(event, commandCode, tenantId, DEFAULT_MAX_RETRIES);
    }

    @Override
    public void write(Object event, String commandCode, Long tenantId, int maxRetries) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String eventId = extractEventId(event);
            String eventType = event.getClass().getSimpleName();

            OutboxEvent outboxEvent = new OutboxEvent();
            outboxEvent.setTenantId(tenantId);
            outboxEvent.setEventId(eventId);
            outboxEvent.setEventType(eventType);
            outboxEvent.setCommandCode(commandCode);
            outboxEvent.setPayload(payload);
            outboxEvent.setStatus(OutboxStatus.PENDING.name());
            outboxEvent.setRetryCount(0);
            outboxEvent.setMaxRetries(maxRetries);
            outboxEvent.setNextRetryAt(Instant.now());
            outboxEvent.setCreatedAt(Instant.now());

            outboxEventMapper.insertEvent(outboxEvent);
            log.debug("Wrote event {} to outbox for command {}", eventId, commandCode);
        } catch (Exception e) {
            log.error("Failed to write event to outbox: {}", e.getMessage(), e);
            throw new BusinessException("Failed to write event to outbox", e);
        }
    }

    /**
     * Extract eventId from known event types, or generate a new one.
     */
    private String extractEventId(Object event) {
        if (event instanceof CommandExecutedEvent e) {
            return e.getEventId();
        } else if (event instanceof DecisionEvent e) {
            return e.getEventId();
        } else if (event instanceof StateTransitionEvent e) {
            return e.getEventId();
        }
        return UniqueIdGenerator.generate();
    }
}
