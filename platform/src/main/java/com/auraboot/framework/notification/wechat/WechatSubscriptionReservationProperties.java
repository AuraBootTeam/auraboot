package com.auraboot.framework.notification.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

/** Deployment-owned mapping from an already authorized source plan; no default recipients or message fields. */
@Component
@ConfigurationProperties(prefix = "aura.notification.wechat-subscription.reservations", ignoreUnknownFields = false)
public class WechatSubscriptionReservationProperties {
    public enum Value { TITLE, CONTENT, SOURCE_ID, OCCURRED_AT }
    private boolean enabled;
    private Set<String> sourceTypes = Set.of();
    private Map<String, Value> fieldSources = Map.of();
    private Map<String, Route> routes = Map.of();
    public Map<String, Route> getRoutes() { return routes; }
    public void setRoutes(Map<String, Route> routes) { this.routes = Map.copyOf(routes); }
    public static class Route {
        private String templateId;
        private Map<String, Value> fieldSources = Map.of();
        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }
        public Map<String, Value> getFieldSources() { return fieldSources; }
        public void setFieldSources(Map<String, Value> fieldSources) { this.fieldSources = Map.copyOf(fieldSources); }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<String> getSourceTypes() { return sourceTypes; }
    public void setSourceTypes(Set<String> sourceTypes) { this.sourceTypes = Set.copyOf(sourceTypes); }
    public Map<String, Value> getFieldSources() { return fieldSources; }
    public void setFieldSources(Map<String, Value> fieldSources) { this.fieldSources = Map.copyOf(fieldSources); }
}
