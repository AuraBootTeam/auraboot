package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * Internal DTO carrying all data needed to create an audit trail record.
 * Built by event listeners and passed to AuditTrailService.recordAudit().
 *
 * @since 6.1.0
 */
@Data
@Builder
public class AuditTrailEvent {

    private Long tenantId;
    private String eventType;
    private String entityType;
    private Long entityId;
    private String commandCode;
    private String operationType;
    private Long actorId;
    private String actorName;
    private String actorIp;
    private JsonNode beforeSnapshot;
    private JsonNode afterSnapshot;
    private String[] changedFields;
    private JsonNode metadata;
}
