package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.entity.AuditTrail;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

/**
 * Public audit-trail response contract.
 *
 * Internal row identifiers, tenant ids, actor ids, raw snapshots, and hash-chain
 * fields stay server-side. Admin verification endpoints still use the full
 * entity internally.
 */
@Data
public class AuditTrailPublicDTO {
    private Long sequenceNo;
    private String eventType;
    private String entityType;
    private String entityPid;
    private String commandCode;
    private String operationType;
    private String actorName;
    private Instant timestamp;
    private String[] changedFields;
    private JsonNode metadata;

    public static AuditTrailPublicDTO from(AuditTrail trail) {
        AuditTrailPublicDTO dto = new AuditTrailPublicDTO();
        if (trail == null) {
            return dto;
        }
        dto.setSequenceNo(trail.getSequenceNo());
        dto.setEventType(trail.getEventType());
        dto.setEntityType(trail.getEntityType());
        dto.setEntityPid(trail.getEntityPid());
        dto.setCommandCode(trail.getCommandCode());
        dto.setOperationType(trail.getOperationType());
        dto.setActorName(trail.getActorName());
        dto.setTimestamp(trail.getTimestamp());
        dto.setChangedFields(trail.getChangedFields());
        dto.setMetadata(trail.getMetadata());
        return dto;
    }
}
