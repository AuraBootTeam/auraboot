package com.auraboot.framework.meta.registry;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open registry for automation action metadata.
 */
@Component
public class AutomationActionRegistry {

    public record ActionMeta(String code, String source, String description) {}

    private final ConcurrentHashMap<String, ActionMeta> entries = new ConcurrentHashMap<>();

    public void register(ActionMeta meta) {
        entries.put(meta.code(), meta);
    }

    public boolean isRegistered(String code) {
        return entries.containsKey(code);
    }

    public Collection<ActionMeta> getAll() {
        return entries.values();
    }

    public List<Map<String, Object>> exportEntries() {
        return entries.values().stream()
                .map(m -> Map.<String, Object>of(
                        "code", m.code(),
                        "source", m.source(),
                        "description", m.description()
                ))
                .toList();
    }
}
