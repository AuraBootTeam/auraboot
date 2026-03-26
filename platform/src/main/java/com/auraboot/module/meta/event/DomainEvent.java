package com.auraboot.module.meta.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.Map;

/**
 * Base domain event that publishes via Spring ApplicationEvent.
 * Works as an in-process adapter alongside the existing outbox pattern,
 * allowing listeners to react within the same transaction.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Getter
public class DomainEvent extends ApplicationEvent {

    private final Long tenantId;
    private final String recordId;
    private final String modelCode;
    private final Map<String, Object> payload;
    private final Instant occurredAt;

    public DomainEvent(Object source, Long tenantId, String recordId,
                       String modelCode, Map<String, Object> payload) {
        super(source);
        this.tenantId = tenantId;
        this.recordId = recordId;
        this.modelCode = modelCode;
        this.payload = payload != null ? Map.copyOf(payload) : Map.of();
        this.occurredAt = Instant.now();
    }
}
