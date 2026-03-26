package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.entity.EventSnapshot;
import com.auraboot.framework.meta.entity.EventStoreEntry;
import com.auraboot.framework.meta.mapper.EventSnapshotMapper;
import com.auraboot.framework.meta.mapper.EventStoreMapper;
import com.auraboot.framework.meta.service.EventStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EventStore implementation.
 * Provides event sourcing: append with optimistic concurrency, query, replay with snapshots.
 *
 * Inherits transaction from caller (no @Transactional on its own) to ensure
 * atomicity with business data and outbox writes.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventStoreImpl implements EventStore {

    private static final int SNAPSHOT_INTERVAL = 50;

    private final EventStoreMapper eventStoreMapper;
    private final EventSnapshotMapper eventSnapshotMapper;
    private final ObjectMapper objectMapper;

    @Override
    public EventStoreEntry append(Long tenantId, String eventType, String aggregateType,
                                   String aggregateId, String payload, Map<String, Object> metadata) {
        int currentVersion = eventStoreMapper.findMaxVersion(tenantId, aggregateType, aggregateId);
        int nextVersion = currentVersion + 1;

        EventStoreEntry entry = new EventStoreEntry();
        entry.setTenantId(tenantId);
        entry.setEventId(UniqueIdGenerator.generate());
        entry.setEventType(eventType);
        entry.setAggregateType(aggregateType);
        entry.setAggregateId(aggregateId);
        entry.setVersion(nextVersion);
        entry.setPayload(payload);
        entry.setMetadata(serializeMetadata(metadata));
        entry.setOccurredAt(Instant.now());
        entry.setCreatedAt(Instant.now());

        try {
            eventStoreMapper.insertEvent(entry);
        } catch (DuplicateKeyException e) {
            log.warn("Version conflict for aggregate {}/{}/{} at version {}",
                    tenantId, aggregateType, aggregateId, nextVersion);
            throw new ConcurrencyException(tenantId, aggregateType, aggregateId, nextVersion);
        }

        log.debug("Appended event {} to store: {}/{} v{}",
                entry.getEventId(), aggregateType, aggregateId, nextVersion);

        // Auto-snapshot if interval reached
        if (nextVersion % SNAPSHOT_INTERVAL == 0) {
            try {
                createSnapshot(tenantId, aggregateType, aggregateId);
                log.info("Auto-snapshot created for {}/{} at version {}",
                        aggregateType, aggregateId, nextVersion);
            } catch (Exception e) {
                log.warn("Auto-snapshot failed for {}/{}: {}",
                        aggregateType, aggregateId, e.getMessage());
            }
        }

        return entry;
    }

    @Override
    public List<EventStoreEntry> getEvents(Long tenantId, String aggregateType, String aggregateId) {
        return eventStoreMapper.findAllEvents(tenantId, aggregateType, aggregateId);
    }

    @Override
    public List<EventStoreEntry> getEventsSince(Long tenantId, String aggregateType,
                                                 String aggregateId, int sinceVersion) {
        return eventStoreMapper.findEventsSinceVersion(tenantId, aggregateType, aggregateId, sinceVersion);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> replay(Long tenantId, String aggregateType, String aggregateId) {
        // 1. Load latest snapshot
        EventSnapshot snapshot = eventSnapshotMapper.findLatestSnapshot(tenantId, aggregateType, aggregateId);

        Map<String, Object> state;
        int sinceVersion;

        if (snapshot != null) {
            state = deserializeState(snapshot.getState());
            sinceVersion = snapshot.getVersion();
            log.debug("Replay from snapshot at version {} for {}/{}",
                    sinceVersion, aggregateType, aggregateId);
        } else {
            state = new LinkedHashMap<>();
            sinceVersion = 0;
            log.debug("Replay from scratch (no snapshot) for {}/{}",
                    aggregateType, aggregateId);
        }

        // 2. Load events since snapshot
        List<EventStoreEntry> events = eventStoreMapper.findEventsSinceVersion(
                tenantId, aggregateType, aggregateId, sinceVersion);

        // 3. Apply events in order
        for (EventStoreEntry event : events) {
            applyEvent(state, event);
        }

        // Add metadata to result
        state.put("_aggregateType", aggregateType);
        state.put("_aggregateId", aggregateId);
        state.put("_version", events.isEmpty() ? sinceVersion :
                events.get(events.size() - 1).getVersion());
        state.put("_eventCount", (sinceVersion > 0 ? sinceVersion : 0) + events.size());

        return state;
    }

    @Override
    public EventSnapshot createSnapshot(Long tenantId, String aggregateType, String aggregateId) {
        Map<String, Object> state = replay(tenantId, aggregateType, aggregateId);
        int currentVersion = eventStoreMapper.findMaxVersion(tenantId, aggregateType, aggregateId);

        if (currentVersion == 0) {
            throw new IllegalStateException(
                    "No events found for aggregate " + aggregateType + "/" + aggregateId);
        }

        // Remove internal metadata from state before persisting
        Map<String, Object> cleanState = new LinkedHashMap<>(state);
        cleanState.remove("_aggregateType");
        cleanState.remove("_aggregateId");
        cleanState.remove("_version");
        cleanState.remove("_eventCount");

        EventSnapshot snapshot = new EventSnapshot();
        snapshot.setTenantId(tenantId);
        snapshot.setAggregateType(aggregateType);
        snapshot.setAggregateId(aggregateId);
        snapshot.setVersion(currentVersion);
        snapshot.setState(serializeState(cleanState));
        snapshot.setMetadata(serializeMetadata(Map.of(
                "createdReason", "auto",
                "eventCount", currentVersion
        )));
        snapshot.setCreatedAt(Instant.now());

        eventSnapshotMapper.insertSnapshot(snapshot);

        // Cleanup older snapshots (keep only recent ones)
        eventSnapshotMapper.deleteOlderSnapshots(tenantId, aggregateType, aggregateId,
                currentVersion - SNAPSHOT_INTERVAL);

        return snapshot;
    }

    @Override
    public int getCurrentVersion(Long tenantId, String aggregateType, String aggregateId) {
        return eventStoreMapper.findMaxVersion(tenantId, aggregateType, aggregateId);
    }

    @Override
    public List<EventStoreEntry> getEventStream(Long tenantId, String aggregateType,
                                                 String aggregateId, int page, int size) {
        // page is 0-based index, so offset = page * size
        int offset = Math.max(0, page) * size;
        return eventStoreMapper.findEventsPaginated(tenantId, aggregateType, aggregateId, size, offset);
    }

    // ==================== Private Helpers ====================

    @SuppressWarnings("unchecked")
    private void applyEvent(Map<String, Object> state, EventStoreEntry event) {
        try {
            Map<String, Object> eventPayload = objectMapper.readValue(
                    event.getPayload(), new TypeReference<Map<String, Object>>() {});

            Object payloadData = eventPayload.get("payload");
            Object resultData = eventPayload.get("result");

            if (payloadData instanceof Map) {
                state.putAll((Map<String, Object>) payloadData);
            }
            if (resultData instanceof Map) {
                state.putAll((Map<String, Object>) resultData);
            }

            state.put("_lastEventType", event.getEventType());
            state.put("_lastEventAt", event.getOccurredAt().toString());
        } catch (Exception e) {
            log.warn("Failed to apply event {} (version {}): {}",
                    event.getEventId(), event.getVersion(), e.getMessage());
        }
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata != null ? metadata : Map.of());
        } catch (Exception e) {
            log.warn("Failed to serialize metadata: {}", e.getMessage());
            return "{}";
        }
    }

    private String serializeState(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new BusinessException("Failed to serialize aggregate state", e);
        }
    }

    private Map<String, Object> deserializeState(String stateJson) {
        try {
            return objectMapper.readValue(stateJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize snapshot state: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    // ==================== Inner Exception ====================

    /**
     * Thrown when optimistic concurrency control detects a version conflict.
     */
    @Getter
    public static class ConcurrencyException extends RuntimeException {
        private final Long tenantId;
        private final String aggregateType;
        private final String aggregateId;
        private final int conflictVersion;

        public ConcurrencyException(Long tenantId, String aggregateType,
                                     String aggregateId, int conflictVersion) {
            super(String.format("Version conflict: aggregate %s/%s at version %d (tenant %d)",
                    aggregateType, aggregateId, conflictVersion, tenantId));
            this.tenantId = tenantId;
            this.aggregateType = aggregateType;
            this.aggregateId = aggregateId;
            this.conflictVersion = conflictVersion;
        }
    }
}
