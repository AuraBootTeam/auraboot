package com.auraboot.framework.meta.event;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.event.DomainEventType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

/**
 * Event published when a state transition occurs.
 * Standalone POJO for outbox serialization — NOT a Spring ApplicationEvent.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Getter
public class StateTransitionEvent {

    private final String eventId;
    private final String eventType;
    private final Instant timestamp;
    private final String modelCode;
    private final String recordId;
    private final String fromState;
    private final String toState;
    private final String triggerCommand;
    private final Long tenantId;
    private final Long userId;

    @JsonCreator
    public StateTransitionEvent(@JsonProperty("modelCode") String modelCode,
                                @JsonProperty("recordId") String recordId,
                                @JsonProperty("fromState") String fromState,
                                @JsonProperty("toState") String toState,
                                @JsonProperty("triggerCommand") String triggerCommand,
                                @JsonProperty("tenantId") Long tenantId,
                                @JsonProperty("userId") Long userId) {
        this.eventId = UniqueIdGenerator.generate();
        this.eventType = DomainEventType.STATE_TRANSITION.getValue();
        this.timestamp = Instant.now();
        this.modelCode = modelCode;
        this.recordId = recordId;
        this.fromState = fromState;
        this.toState = toState;
        this.triggerCommand = triggerCommand;
        this.tenantId = tenantId;
        this.userId = userId;
    }
}
