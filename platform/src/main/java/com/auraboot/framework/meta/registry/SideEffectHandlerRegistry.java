package com.auraboot.framework.meta.registry;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open registry for side-effect handler metadata.
 */
@Component
public class SideEffectHandlerRegistry {

    public record HandlerMeta(String code, String source, String description) {}

    private final ConcurrentHashMap<String, HandlerMeta> entries = new ConcurrentHashMap<>();

    public void register(HandlerMeta meta) {
        entries.put(meta.code(), meta);
    }

    public boolean isRegistered(String code) {
        return entries.containsKey(code);
    }

    public Collection<HandlerMeta> getAll() {
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
