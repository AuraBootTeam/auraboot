package com.auraboot.framework.agent.service;

import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for plugin import events and triggers capability sync
 * to materialize capabilities into the ab_capability table.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilitySyncListener {

    private final CapabilityViewService capabilityViewService;

    @EventListener
    public void onPluginImportCompleted(PluginImportCompletedEvent event) {
        log.info("Plugin import completed for plugin '{}', triggering capability sync for tenant {}",
                event.getPluginCode(), event.getTenantId());
        capabilityViewService.syncCapabilities(event.getTenantId());
    }
}
