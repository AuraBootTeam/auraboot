package com.auraboot.framework.view.dto;

import com.auraboot.framework.meta.entity.AuditTrail;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Data
public class SavedViewAuditEventDTO {
    private String eventType;
    private String entityType;
    private String entityPid;
    private String commandCode;
    private String operationType;
    private String actorName;
    private Instant timestamp;
    private List<String> changedFields;
    private JsonNode metadata;

    public static SavedViewAuditEventDTO from(AuditTrail trail) {
        if (trail == null) {
            return null;
        }
        SavedViewAuditEventDTO dto = new SavedViewAuditEventDTO();
        dto.setEventType(trail.getEventType());
        dto.setEntityType(trail.getEntityType());
        dto.setEntityPid(trail.getEntityPid());
        dto.setCommandCode(trail.getCommandCode());
        dto.setOperationType(trail.getOperationType());
        dto.setActorName(trail.getActorName());
        dto.setTimestamp(trail.getTimestamp());
        dto.setChangedFields(trail.getChangedFields() == null
                ? List.of()
                : Arrays.asList(trail.getChangedFields()));
        dto.setMetadata(trail.getMetadata());
        return dto;
    }
}
