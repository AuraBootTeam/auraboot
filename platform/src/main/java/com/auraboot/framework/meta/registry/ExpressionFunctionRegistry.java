package com.auraboot.framework.meta.registry;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open registry for expression function metadata.
 */
@Component
public class ExpressionFunctionRegistry {

    public record FunctionMeta(String name, String source, String type, String description) {}

    private final ConcurrentHashMap<String, FunctionMeta> entries = new ConcurrentHashMap<>();

    public void register(FunctionMeta meta) {
        entries.put(meta.name(), meta);
    }

    public boolean isRegistered(String name) {
        return entries.containsKey(name);
    }

    public Collection<FunctionMeta> getAll() {
        return entries.values();
    }

    public List<Map<String, Object>> exportEntries() {
        return entries.values().stream()
                .map(m -> Map.<String, Object>of(
                        "name", m.name(),
                        "source", m.source(),
                        "type", m.type(),
                        "description", m.description()
                ))
                .toList();
    }
}
