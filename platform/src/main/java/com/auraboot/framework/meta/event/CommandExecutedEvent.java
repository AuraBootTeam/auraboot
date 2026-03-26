package com.auraboot.framework.meta.event;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.event.DomainEventType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * Event published after successful command execution (EFFECT phase).
 * Standalone POJO for outbox serialization — NOT a Spring ApplicationEvent.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Getter
public class CommandExecutedEvent {

    private final String eventId;
    private final String eventType;
    private final Instant timestamp;
    private final String commandCode;
    private final String modelCode;
    private final Map<String, Object> payload;
    private final Map<String, Object> result;
    private final Long tenantId;
    private final Long userId;

    @JsonCreator
    public CommandExecutedEvent(@JsonProperty("commandCode") String commandCode,
                                @JsonProperty("modelCode") String modelCode,
                                @JsonProperty("payload") Map<String, Object> payload,
                                @JsonProperty("result") Map<String, Object> result,
                                @JsonProperty("tenantId") Long tenantId,
                                @JsonProperty("userId") Long userId) {
        this.eventId = UniqueIdGenerator.generate();
        this.eventType = DomainEventType.COMMAND_EXECUTED.getValue();
        this.timestamp = Instant.now();
        this.commandCode = commandCode;
        this.modelCode = modelCode;
        this.payload = payload;
        this.result = result;
        this.tenantId = tenantId;
        this.userId = userId;
    }
}
