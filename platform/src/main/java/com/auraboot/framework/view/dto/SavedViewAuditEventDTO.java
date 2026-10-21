package com.auraboot.framework.view.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.auraboot.framework.meta.entity.AuditTrail;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Data
public class SavedViewAuditEventDTO {
    @Schema(nullable = true)
    private Long sequenceNo;
    @Schema(nullable = true)
    private String eventType;
    @Schema(nullable = true)
    private String entityType;
    @Schema(nullable = true)
    private String entityPid;
    @Schema(nullable = true)
    private String commandCode;
    @Schema(nullable = true)
    private String operationType;
    @Schema(nullable = true)
    private String actorName;
    @Schema(nullable = true)
    private Instant timestamp;
    @Schema(nullable = true)
    private List<String> changedFields;
    @Schema(implementation = Object.class)
    private JsonNode metadata;

    public static SavedViewAuditEventDTO from(AuditTrail trail) {
        if (trail == null) {
            return null;
        }
        SavedViewAuditEventDTO dto = new SavedViewAuditEventDTO();
        dto.setSequenceNo(trail.getSequenceNo());
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
