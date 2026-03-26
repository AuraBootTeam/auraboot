package com.auraboot.framework.meta.registry;

import com.auraboot.framework.meta.constant.DslRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * Runtime-extensible chart type registry.
 * <p>
 * Initializes with all {@link DslRegistry.ChartType} enum values (built-in),
 * then plugins or custom code can register additional chart types at runtime.
 * <p>
 * This is the runtime authority for chart type validation; the enum remains
 * the compile-time reference for known built-in types.
 */
@Slf4j
@Component
public class ChartTypeRegistry {

    private final OpenDslEnumRegistry registry = new OpenDslEnumRegistry();

    @PostConstruct
    void initialize() {
        // Seed from the compile-time enum (all 24 built-in types)
        registry.seedFromEnum(DslRegistry.ChartType.class);
        log.info("ChartTypeRegistry initialized with {} built-in chart types", registry.size());
    }

    /**
     * Register a custom chart type at runtime (e.g., from a plugin).
     * Built-in types cannot be overwritten.
     *
     * @return true if the type was newly registered
     */
    public boolean register(String code, String label, String since) {
        boolean added = registry.register(code, label, since);
        if (added) {
            log.info("Registered custom chart type: {} ({})", code, label);
        }
        return added;
    }

    /** Get all known chart type codes. */
    public Set<String> allCodes() {
        return registry.allCodes();
    }

    /** Get all entries (built-in + runtime). */
    public Collection<OpenDslEnumRegistry.Entry> allEntries() {
        return registry.allEntries();
    }

    /** Export all entries as a list of maps for JSON serialization. */
    public List<Map<String, String>> exportEntries() {
        return registry.exportEntries();
    }

    /** Check if a chart type code is known. */
    public boolean isKnown(String code) {
        return registry.isKnown(code);
    }

    /** Get the total count of registered chart types. */
    public int size() {
        return registry.size();
    }

    // ═══════════════════════════════════════════════════════════════
    // Plugin registration hook
    // ═══════════════════════════════════════════════════════════════

    /**
     * Register chart types declared in a plugin manifest.
     * <p>
     * Expected format in plugin manifest JSON:
     * <pre>{@code
     * "chartTypes": [
     *   { "code": "custom-chart", "label": "My Custom Chart" },
     *   { "code": "another-chart", "label": "Another Chart", "since": "1.0" }
     * ]
     * }</pre>
     * <p>
     * Called during plugin import when the manifest declares custom chart types.
     * This is a future-ready hook — plugin manifest schema does not yet include
     * "chartTypes", but the processing logic is ready.
     *
     * @param chartTypeDeclarations list of maps with "code" and "label" keys
     * @return number of newly registered chart types
     */
    @SuppressWarnings("unchecked")
    public int registerFromPluginManifest(List<Map<String, Object>> chartTypeDeclarations) {
        if (chartTypeDeclarations == null || chartTypeDeclarations.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Map<String, Object> decl : chartTypeDeclarations) {
            String code = Objects.toString(decl.get("code"), null);
            String label = Objects.toString(decl.get("label"), code);
            String since = Objects.toString(decl.get("since"), "plugin");
            if (code != null && !code.isBlank()) {
                if (register(code, label, since)) {
                    count++;
                }
            }
        }
        if (count > 0) {
            log.info("Registered {} chart types from plugin manifest", count);
        }
        return count;
    }
}
