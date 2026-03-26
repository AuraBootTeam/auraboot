package com.auraboot.framework.meta.registry;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open registry for command handler metadata.
 * Plugins register their custom command handlers at startup.
 */
@Component
public class CommandHandlerRegistry {

    public record HandlerMeta(String code, String source, String description, String targetModel, String riskLevel) {}

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
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("code", m.code());
                    map.put("source", m.source());
                    map.put("description", m.description());
                    if (m.targetModel() != null) map.put("targetModel", m.targetModel());
                    if (m.riskLevel() != null) map.put("riskLevel", m.riskLevel());
                    return map;
                })
                .toList();
    }
}
