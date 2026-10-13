package com.auraboot.framework.notification.sourcebinding;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment only. No API or plugin settings registration. Default empty and disabled. */
@Component
@ConfigurationProperties(prefix = "aura.notification.source-bindings", ignoreUnknownFields = false)
public class HostSourceBindingProperties {
    private boolean enabled;
    private List<SourceBindingTypes.Binding> bindings = List.of();
    public boolean isEnabled() {
        return enabled;
    }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    public List<SourceBindingTypes.Binding> getBindings() {
        return bindings;
    }
    public void setBindings(List<SourceBindingTypes.Binding> bindings) {
        this.bindings = List.copyOf(bindings);
    }
}
