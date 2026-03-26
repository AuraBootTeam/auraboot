package com.auraboot.framework.event;

/**
 * Dispatches events to plugin EventListenerExtension implementations.
 * Injected into AuraEventBus as optional dependency.
 *
 * @since 6.0.0
 */
public interface PluginEventDispatcher {
    void dispatch(AuraEvent event);
}
