package com.auraboot.framework.bpm.event;

import com.auraboot.framework.event.AuraEvent;
import lombok.Getter;

import java.util.Map;

/**
 * BPM event model extending the unified AuraEvent base.
 *
 * Since AuraEvent fields (eventId, occurredAt, tenantId) are final and auto-generated,
 * all fields must be set at construction time. Use the constructor or the static factory
 * method {@link #of(Long, String, String, String, String, String, Map)}.
 *
 * The inherited {@link #getEventType()} returns a prefixed form like "bpm:process_started".
 * Use {@link #getBpmEventType()} to get the raw BPM type like "process_started".
 *
 * @since 6.0.0
 */
@Getter
public class BpmEvent extends AuraEvent {

    private final String sourceType;
    private final String processKey;
    private final String instanceId;
    private final String nodeId;

    public BpmEvent(Long tenantId, String eventType, String sourceType,
                    String processKey, String instanceId, String nodeId,
                    Map<String, Object> payload) {
        super(tenantId, "bpm:" + eventType.toLowerCase(), null, instanceId, payload);
        this.sourceType = sourceType != null ? sourceType : "bpm";
        this.processKey = processKey;
        this.instanceId = instanceId;
        this.nodeId = nodeId;
    }

    /**
     * Static factory method for convenience.
     */
    public static BpmEvent of(Long tenantId, String eventType, String sourceType,
                               String processKey, String instanceId, String nodeId,
                               Map<String, Object> payload) {
        return new BpmEvent(tenantId, eventType, sourceType, processKey, instanceId, nodeId, payload);
    }

    /**
     * Get raw BPM event type (without "bpm:" prefix).
     * E.g. returns "process_started" when eventType is "bpm:process_started".
     */
    public String getBpmEventType() {
        String et = getEventType();
        if (et != null && et.startsWith("bpm:")) {
            return et.substring(4);
        }
        return et;
    }
}
