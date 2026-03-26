package com.auraboot.module.meta.event;

import com.auraboot.framework.event.AuraEvent;
import lombok.Getter;

import java.util.Map;

/**
 * Event published after a command completes execution successfully.
 * Extends AuraEvent with command-specific context (commandCode, operationType).
 *
 * @author AuraBoot Team
 * @since 6.0.0
 */
@Getter
public class CommandCompletedEvent extends AuraEvent {

    private final String commandCode;
    private final String operationType;

    public CommandCompletedEvent(Long tenantId, String recordId,
                                 String modelCode, Map<String, Object> payload,
                                 String commandCode, String operationType) {
        super(tenantId, "command:completed", modelCode, recordId, payload);
        this.commandCode = commandCode;
        this.operationType = operationType;
    }
}
