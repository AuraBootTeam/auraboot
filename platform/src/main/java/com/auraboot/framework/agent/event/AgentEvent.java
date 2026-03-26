package com.auraboot.framework.agent.event;

import com.auraboot.framework.event.AuraEvent;
import lombok.Getter;

import java.util.Map;

/**
 * Domain event for agent activities.
 * Published via Spring ApplicationEventPublisher, consumed by AgentObservationService.
 */
public class AgentEvent extends AuraEvent {

    @Getter
    private final String agentEventType;
    @Getter
    private final String agentId;

    public AgentEvent(Long tenantId, String agentEventType, String agentId,
                      String modelCode, String recordId, Map<String, Object> payload) {
        super(tenantId, "agent_" + agentEventType, modelCode, recordId, payload);
        this.agentEventType = agentEventType;
        this.agentId = agentId;
    }
}
