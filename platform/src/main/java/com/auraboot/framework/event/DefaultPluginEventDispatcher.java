package com.auraboot.framework.event;

import com.auraboot.framework.plugin.extension.EventListenerExtension;
import com.auraboot.framework.plugin.extension.EventListenerExtension.EventContext;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default implementation that bridges AuraEvent to plugin EventListenerExtension instances.
 * Retrieves matching listeners from ExtensionRegistry and dispatches synchronously,
 * isolating each listener so a single failure does not block others.
 *
 * @since 6.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPluginEventDispatcher implements PluginEventDispatcher {

    private final ExtensionRegistry extensionRegistry;

    @Override
    public void dispatch(AuraEvent event) {
        String eventType = event.getEventType();
        List<EventListenerExtension> listeners;
        try {
            listeners = extensionRegistry.getEventListeners(eventType);
        } catch (Exception e) {
            log.warn("Failed to get plugin event listeners for {}: {}", eventType, e.getMessage());
            return;
        }

        if (listeners.isEmpty()) {
            return;
        }

        log.debug("Dispatching event {} to {} plugin listeners", eventType, listeners.size());

        for (EventListenerExtension listener : listeners) {
            EventContext ctx = new EventContext(
                    event.getTenantId(),
                    null,  // pluginId resolved by pf4j
                    null,  // namespace
                    eventType,
                    event.getModelCode(),
                    event.getRecordId(),
                    event.getPayload(),
                    null,  // previousData
                    event.getOccurredAt().toEpochMilli()
            );

            try {
                listener.onEvent(ctx);
            } catch (Exception e) {
                log.error("Plugin event listener failed for {}: {}",
                        eventType, e.getMessage(), e);
            }
        }
    }
}
