package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Event Stream response DTO for admin viewing.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@Builder
public class EventStreamDTO {

    private String aggregateType;
    private String aggregateId;
    private int currentVersion;
    private int totalEvents;
    private List<EventEntryDTO> events;

    @Data
    @Builder
    public static class EventEntryDTO {
        private String eventId;
        private String eventType;
        private int version;
        private Map<String, Object> payload;
        private Map<String, Object> metadata;
        private String occurredAt;
    }
}
