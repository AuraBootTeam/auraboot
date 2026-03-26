package com.auraboot.module.meta.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Default listener that logs all CommandCompletedEvent instances.
 * Serves as both a diagnostic tool and a reference implementation
 * for custom event listeners.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Component
public class CommandEventLogger {

    @EventListener
    public void onCommandCompleted(CommandCompletedEvent event) {
        log.info("Command completed: {} on {}/{} (op={})",
                event.getCommandCode(), event.getModelCode(),
                event.getRecordId(), event.getOperationType());
    }
}
