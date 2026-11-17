package com.auraboot.framework.notification.sourcebinding;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment only. No API or plugin settings registration. Default empty and disabled. */
@Component
@ConfigurationProperties(prefix = "aura.notification.source-bindings", ignoreUnknownFields = false)
public class HostSourceBindingProperties {
    private boolean enabled;
    private Polling worker = new Polling();
    private List<SourceBindingTypes.Binding> bindings = List.of();
    public Polling getWorker() {
        return worker;
    }
    public void setWorker(Polling worker) {
        this.worker = java.util.Objects.requireNonNull(worker);
    }
    /** Polling bounds and explicit channel opt-in; no reminder timing or recipient policy. */
    public static final class Polling {
        private boolean enabled;
        private boolean wechatSubscriptionEnabled;
        private Long pollIntervalMs;
        private Integer maxSourcesPerBinding;
        private Integer maxBindings;

        public boolean isWechatSubscriptionEnabled() { return wechatSubscriptionEnabled; }
        public void setWechatSubscriptionEnabled(boolean value) { wechatSubscriptionEnabled = value; }

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public Long getPollIntervalMs() {
            return pollIntervalMs;
        }
        public void setPollIntervalMs(Long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }
        public Integer getMaxSourcesPerBinding() {
            return maxSourcesPerBinding;
        }
        public void setMaxSourcesPerBinding(Integer maxSourcesPerBinding) {
            this.maxSourcesPerBinding = maxSourcesPerBinding;
        }
        public Integer getMaxBindings() {
            return maxBindings;
        }
        public void setMaxBindings(Integer maxBindings) {
            this.maxBindings = maxBindings;
        }
    }
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
