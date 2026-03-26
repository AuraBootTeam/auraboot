package com.auraboot.framework.meta.registry;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open registry for block renderer metadata.
 */
@Component
public class BlockRendererRegistry {

    public record RendererMeta(String code, String source, String description) {}

    private final ConcurrentHashMap<String, RendererMeta> entries = new ConcurrentHashMap<>();

    public void register(RendererMeta meta) {
        entries.put(meta.code(), meta);
    }

    public boolean isRegistered(String code) {
        return entries.containsKey(code);
    }

    public Collection<RendererMeta> getAll() {
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
