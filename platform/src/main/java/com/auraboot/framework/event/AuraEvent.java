package com.auraboot.framework.event;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Unified base class for all domain events in AuraBoot.
 *
 * Extends Spring ApplicationEvent so all existing @EventListener / @TransactionalEventListener
 * consumers continue to work without changes. Adds eventId (ULID), eventType, tenantId,
 * and optional model/record context.
 *
 * @since 6.0.0
 */
public abstract class AuraEvent extends ApplicationEvent {

    @Getter
    private final String eventId;
    @Getter
    private final String eventType;
    @Getter
    private final Long tenantId;
    @Getter
    private final String modelCode;
    @Getter
    private final String recordId;
    @Getter
    private final Map<String, Object> payload;
    @Getter
    private final Instant occurredAt;

    private final Map<String, Object> mutableMetadata;

    protected AuraEvent(Long tenantId, String eventType,
                        String modelCode, String recordId,
                        Map<String, Object> payload) {
        super("AuraEventBus");
        this.eventId = UniqueIdGenerator.generate();
        this.eventType = eventType;
        this.tenantId = tenantId;
        this.modelCode = modelCode;
        this.recordId = recordId;
        this.payload = payload != null ? Map.copyOf(payload) : Map.of();
        this.occurredAt = Instant.now();
        this.mutableMetadata = new HashMap<>();
    }

    /**
     * Add metadata before publishing. Should only be called during event construction,
     * not after the event has been published.
     */
    public void addMetadata(String key, Object value) {
        this.mutableMetadata.put(key, value);
    }

    /**
     * Returns an unmodifiable view of the metadata map.
     * Prevents listeners from modifying metadata after event publication.
     */
    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(mutableMetadata);
    }
}
