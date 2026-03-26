package com.auraboot.framework.meta.registry;

import com.auraboot.framework.meta.constant.DslRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic open enum registry that merges compile-time {@link DslRegistry.DslEnum}
 * values with runtime-registered entries.
 * <p>
 * Thread-safe via {@link ConcurrentHashMap}. Enum-sourced entries are always
 * included and cannot be removed at runtime.
 * <p>
 * Designed to be reusable for any DSL enum that needs runtime extensibility
 * (e.g., ChartType, RenderComponent, BlockType).
 */
public class OpenDslEnumRegistry {

    /** Immutable entry representing a DSL enum value. */
    public record Entry(String code, String label, String since, boolean builtIn) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Seed the registry with all values from a compile-time DslEnum.
     * These entries are marked as built-in and cannot be overwritten by runtime registration.
     */
    public <E extends Enum<E> & DslRegistry.DslEnum> void seedFromEnum(Class<E> enumClass) {
        for (E constant : enumClass.getEnumConstants()) {
            entries.put(constant.code(), new Entry(constant.code(), constant.label(), constant.since(), true));
        }
    }

    /**
     * Register a runtime entry. If the code already exists as a built-in entry, it is NOT overwritten.
     *
     * @return true if the entry was newly registered, false if a built-in entry already existed
     */
    public boolean register(String code, String label, String since) {
        Entry existing = entries.putIfAbsent(code, new Entry(code, label, since, false));
        return existing == null;
    }

    /** Get all registered codes (built-in + runtime). */
    public Set<String> allCodes() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /** Get all entries as an unmodifiable collection. */
    public Collection<Entry> allEntries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    /** Check if a code is known (either built-in or runtime-registered). */
    public boolean isKnown(String code) {
        return entries.containsKey(code);
    }

    /** Get the total count of registered entries. */
    public int size() {
        return entries.size();
    }

    /**
     * Export all entries as a list of maps, suitable for JSON serialization.
     * Ordered by: built-in first, then runtime, alphabetically within each group.
     */
    public List<Map<String, String>> exportEntries() {
        return entries.values().stream()
                .sorted(Comparator.comparing((Entry e) -> !e.builtIn())
                        .thenComparing(Entry::code))
                .map(e -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    map.put("code", e.code());
                    map.put("label", e.label());
                    map.put("since", e.since());
                    map.put("source", e.builtIn() ? "built-in" : "runtime");
                    return map;
                })
                .toList();
    }
}
