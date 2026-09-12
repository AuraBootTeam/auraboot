package com.auraboot.framework.plugin.extension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Business-neutral workflow lifecycle event published by a workflow provider. */
public class WorkflowEvent {
    private final String eventId = UUID.randomUUID().toString();
    private final Instant occurredAt = Instant.now();
    private final Long tenantId;
    private final String eventType;
    private final String sourceType;
    private final String processKey;
    private final String instanceId;
    private final String nodeId;
    private final Map<String, Object> payload;

    public WorkflowEvent(Long tenantId, String eventType, String sourceType, String processKey,
                         String instanceId, String nodeId, Map<String, Object> payload) {
        this.tenantId = tenantId;
        this.sourceType = sourceType == null ? "workflow" : sourceType;
        this.eventType = eventType == null ? null : this.sourceType.toLowerCase() + ":" + eventType.toLowerCase();
        this.processKey = processKey;
        this.instanceId = instanceId;
        this.nodeId = nodeId;
        this.payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public String getEventId() { return eventId; }
    public Instant getOccurredAt() { return occurredAt; }
    public Long getTenantId() { return tenantId; }
    public String getEventType() { return eventType; }
    public String getSourceType() { return sourceType; }
    public String getProcessKey() { return processKey; }
    public String getInstanceId() { return instanceId; }
    public String getNodeId() { return nodeId; }
    public Map<String, Object> getPayload() { return payload; }
    public String getWorkflowEventType() {
        int separator = eventType == null ? -1 : eventType.indexOf(':');
        return separator >= 0 ? eventType.substring(separator + 1) : eventType;
    }
}
