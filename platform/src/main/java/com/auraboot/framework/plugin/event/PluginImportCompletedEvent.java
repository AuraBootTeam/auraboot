package com.auraboot.framework.plugin.event;

import org.springframework.context.ApplicationEvent;

/**
 * Published after a plugin import completes successfully.
 * Enterprise-ai module listens to trigger capability sync.
 */
public class PluginImportCompletedEvent extends ApplicationEvent {
    private final Long tenantId;
    private final String pluginCode;

    public PluginImportCompletedEvent(Object source, Long tenantId, String pluginCode) {
        super(source);
        this.tenantId = tenantId;
        this.pluginCode = pluginCode;
    }

    public Long getTenantId() { return tenantId; }
    public String getPluginCode() { return pluginCode; }
}
