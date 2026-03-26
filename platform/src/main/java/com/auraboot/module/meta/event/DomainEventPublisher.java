package com.auraboot.module.meta.event;

import com.auraboot.framework.event.AuraEventBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Thin adapter that publishes domain events via AuraEventBus.
 * This delegates to the centralized event bus which dispatches to both
 * Spring @EventListener consumers and plugin-based listeners.
 *
 * @author AuraBoot Team
 * @since 6.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventPublisher {

    private final AuraEventBus auraEventBus;

    /**
     * Publish a CommandCompletedEvent after successful command execution.
     */
    public void publishCommandCompleted(String commandCode, String operationType,
                                         Long tenantId, String recordId,
                                         String modelCode, Map<String, Object> payload) {
        publishCommandCompleted(commandCode, operationType, tenantId, recordId,
                modelCode, payload, null, null);
    }

    /**
     * Publish a CommandCompletedEvent with actor metadata for audit trail.
     */
    public void publishCommandCompleted(String commandCode, String operationType,
                                         Long tenantId, String recordId,
                                         String modelCode, Map<String, Object> payload,
                                         Long actorId, String actorName) {
        publishCommandCompleted(commandCode, operationType, tenantId, recordId,
                modelCode, payload, actorId, actorName, null);
    }

    /**
     * Publish a CommandCompletedEvent with actor metadata and extra context
     * (e.g., beforeSnapshot for field change auditing).
     */
    public void publishCommandCompleted(String commandCode, String operationType,
                                         Long tenantId, String recordId,
                                         String modelCode, Map<String, Object> payload,
                                         Long actorId, String actorName,
                                         Map<String, Object> extraMetadata) {
        var event = new CommandCompletedEvent(
                tenantId, recordId, modelCode, payload, commandCode, operationType);
        if (actorId != null) {
            event.addMetadata("actorId", actorId);
        }
        if (actorName != null) {
            event.addMetadata("actorName", actorName);
        }
        if (extraMetadata != null) {
            extraMetadata.forEach(event::addMetadata);
        }
        log.debug("Publishing CommandCompletedEvent: command={}, model={}, record={}",
                commandCode, modelCode, recordId);
        auraEventBus.publish(event);
    }
}
