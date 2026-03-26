package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.EventStreamDTO;
import com.auraboot.framework.meta.entity.EventStoreEntry;
import com.auraboot.framework.meta.service.EventStore;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Event Store Controller.
 * Admin API for viewing event streams and managing snapshots.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/event-store")
@RequiredArgsConstructor
@Validated
public class EventStoreController {

    private final EventStore eventStore;
    private final ObjectMapper objectMapper;

    /**
     * Get event stream for an aggregate (paginated, newest first).
     */
    @GetMapping("/{aggregateType}/{aggregateId}")
    @RequirePermission(MetaPermission.EVENT_STORE_READ)
    public ApiResponse<EventStreamDTO> getEventStream(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long tenantId = MetaContext.getCurrentTenantId();

        List<EventStoreEntry> events = eventStore.getEventStream(
                tenantId, aggregateType, aggregateId, page, size);
        int currentVersion = eventStore.getCurrentVersion(tenantId, aggregateType, aggregateId);

        List<EventStreamDTO.EventEntryDTO> entries = events.stream()
                .map(this::toEventEntryDTO)
                .collect(Collectors.toList());

        EventStreamDTO dto = EventStreamDTO.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .currentVersion(currentVersion)
                .totalEvents(currentVersion)
                .events(entries)
                .build();

        return ApiResponse.success(dto);
    }

    /**
     * Replay aggregate state from event history.
     */
    @GetMapping("/{aggregateType}/{aggregateId}/replay")
    @RequirePermission(MetaPermission.EVENT_STORE_ADMIN)
    public ApiResponse<Map<String, Object>> replay(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> state = eventStore.replay(tenantId, aggregateType, aggregateId);
        return ApiResponse.success(state);
    }

    /**
     * Create a snapshot for the aggregate (on-demand).
     */
    @PostMapping("/{aggregateType}/{aggregateId}/snapshot")
    @RequirePermission(MetaPermission.EVENT_STORE_ADMIN)
    public ApiResponse<Void> createSnapshot(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId) {

        Long tenantId = MetaContext.getCurrentTenantId();
        eventStore.createSnapshot(tenantId, aggregateType, aggregateId);
        return ApiResponse.success(null);
    }

    // ==================== Private Helpers ====================

    private EventStreamDTO.EventEntryDTO toEventEntryDTO(EventStoreEntry entry) {
        Map<String, Object> payload = Map.of();
        Map<String, Object> metadata = Map.of();
        try {
            if (entry.getPayload() != null) {
                payload = objectMapper.readValue(entry.getPayload(),
                        new TypeReference<Map<String, Object>>() {});
            }
            if (entry.getMetadata() != null) {
                metadata = objectMapper.readValue(entry.getMetadata(),
                        new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to parse event entry {}: {}", entry.getEventId(), e.getMessage());
        }

        return EventStreamDTO.EventEntryDTO.builder()
                .eventId(entry.getEventId())
                .eventType(entry.getEventType())
                .version(entry.getVersion())
                .payload(payload)
                .metadata(metadata)
                .occurredAt(entry.getOccurredAt() != null ? entry.getOccurredAt().toString() : null)
                .build();
    }
}
