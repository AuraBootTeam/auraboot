package com.auraboot.framework.notification.wechat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Explicit template contract; no recipient, template or route defaults. */
@Data
@Component
@ConfigurationProperties(prefix = "aura.notification.wechat-subscription")
public class WechatSubscriptionProperties {
    private boolean enabled;
    private boolean allowLoopback;
    private String templateId;
    private String miniprogramState;
    private Map<String,Integer> fieldMaxLengths = Map.of();
    private Map<String, Template> templates = Map.of();

    @Data
    public static class Template {
        private Map<String, Integer> fieldMaxLengths = Map.of();
    }

    /** Exactly one explicit mode: legacy single-template contract or named template contracts. */
    Map<String, Map<String, Integer>> templateContracts() {
        if (templates == null || fieldMaxLengths == null) return Map.of();
        if (templates.isEmpty()) {
            return validId(templateId) && validFields(fieldMaxLengths)
                    ? Map.of(templateId, Map.copyOf(fieldMaxLengths)) : Map.of();
        }
        if (templates.size() > 100 || templateId != null && !templateId.isBlank() || !fieldMaxLengths.isEmpty())
            return Map.of();
        var result = new java.util.TreeMap<String, Map<String, Integer>>();
        for (var entry : templates.entrySet()) {
            if (!validId(entry.getKey()) || entry.getValue() == null
                    || !validFields(entry.getValue().getFieldMaxLengths())) return Map.of();
            result.put(entry.getKey(), Map.copyOf(entry.getValue().getFieldMaxLengths()));
        }
        return Map.copyOf(result);
    }
    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
    }
    private static boolean validFields(Map<String, Integer> values) {
        return values != null && !values.isEmpty() && values.size() <= 16 && values.entrySet().stream().allMatch(entry ->
                entry.getKey() != null && entry.getKey().matches("[A-Za-z][A-Za-z0-9]*")
                        && entry.getValue() != null && entry.getValue() > 0);
    }
    private long connectTimeoutMs = 10_000;
    private long readTimeoutMs = 10_000;
}
