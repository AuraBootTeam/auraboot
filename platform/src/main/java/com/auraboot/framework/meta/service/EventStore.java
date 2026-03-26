package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.entity.EventSnapshot;
import com.auraboot.framework.meta.entity.EventStoreEntry;

import java.util.List;
import java.util.Map;

/**
 * Event Store service interface.
 * Provides event sourcing capabilities: append, query, replay, and snapshot.
 *
 * Must be called within an existing transaction (EFFECT phase) to guarantee
 * atomicity with business data and outbox writes.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
public interface EventStore {

    /**
     * Append a domain event to the event store.
     * Auto-resolves the next version for the aggregate.
     * Throws ConcurrencyException on version conflict.
     *
     * @param tenantId      tenant identifier
     * @param eventType     event type name
     * @param aggregateType aggregate type (e.g., modelCode)
     * @param aggregateId   aggregate instance ID (e.g., targetRecordId)
     * @param payload       serialized event payload (JSON string)
     * @param metadata      event metadata map (userId, commandCode, etc.)
     * @return the persisted EventStoreEntry with assigned version
     */
    EventStoreEntry append(Long tenantId, String eventType, String aggregateType,
                           String aggregateId, String payload, Map<String, Object> metadata);

    /**
     * Get all events for a specific aggregate, ordered by version ascending.
     */
    List<EventStoreEntry> getEvents(Long tenantId, String aggregateType, String aggregateId);

    /**
     * Get events since a specific version (exclusive).
     */
    List<EventStoreEntry> getEventsSince(Long tenantId, String aggregateType,
                                          String aggregateId, int sinceVersion);

    /**
     * Replay aggregate state from event history.
     * Loads latest snapshot (if exists), then applies subsequent events.
     *
     * @return reconstructed aggregate state as a map
     */
    Map<String, Object> replay(Long tenantId, String aggregateType, String aggregateId);

    /**
     * Create a snapshot of the current aggregate state.
     */
    EventSnapshot createSnapshot(Long tenantId, String aggregateType, String aggregateId);

    /**
     * Get the current version of an aggregate (0 if no events exist).
     */
    int getCurrentVersion(Long tenantId, String aggregateType, String aggregateId);

    /**
     * Get paginated event stream for an aggregate (newest first, for admin viewing).
     */
    List<EventStoreEntry> getEventStream(Long tenantId, String aggregateType,
                                          String aggregateId, int page, int size);
}
