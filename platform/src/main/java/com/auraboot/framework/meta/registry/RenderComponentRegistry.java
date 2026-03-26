package com.auraboot.framework.meta.registry;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open registry for render component metadata.
 */
@Component
public class RenderComponentRegistry {

    public record ComponentMeta(String code, String source, List<String> dataTypes, String category) {}

    private final ConcurrentHashMap<String, ComponentMeta> entries = new ConcurrentHashMap<>();

    public void register(ComponentMeta meta) {
        entries.put(meta.code(), meta);
    }

    public boolean isRegistered(String code) {
        return entries.containsKey(code);
    }

    public Collection<ComponentMeta> getAll() {
        return entries.values();
    }

    public List<Map<String, Object>> exportEntries() {
        return entries.values().stream()
                .map(m -> Map.<String, Object>of(
                        "code", m.code(),
                        "source", m.source(),
                        "dataTypes", m.dataTypes(),
                        "category", m.category()
                ))
                .toList();
    }
}
