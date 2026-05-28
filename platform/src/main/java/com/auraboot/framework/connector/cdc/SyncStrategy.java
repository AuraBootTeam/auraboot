package com.auraboot.framework.connector.cdc;

import java.util.Locale;

/**
 * Four sync strategies supported by AuraBoot connectors (PRD 18 §B.3.1 / Airbyte mapping §10).
 *
 * <ul>
 *   <li>{@link #FULL_REFRESH}       — overwrite destination on every run.</li>
 *   <li>{@link #INCREMENTAL_APPEND} — append rows newer than a cursor field.</li>
 *   <li>{@link #INCREMENTAL_DEDUP}  — incremental append followed by primary-key dedup.</li>
 *   <li>{@link #CDC}                — change-data-capture driven by a {@link CdcEngine}.</li>
 * </ul>
 *
 * <p>Persisted form is the kebab-case yaml value ({@link #yamlValue()}); DB column uses
 * the enum name to keep it stable across yaml renames.
 *
 * @since 5.3.0
 */
public enum SyncStrategy {
    FULL_REFRESH("full-refresh"),
    INCREMENTAL_APPEND("incremental-append"),
    INCREMENTAL_DEDUP("incremental-dedup"),
    CDC("cdc");

    private final String yamlValue;

    SyncStrategy(String yamlValue) {
        this.yamlValue = yamlValue;
    }

    /**
     * Kebab-case identifier used in connector yaml manifests and external APIs.
     */
    public String yamlValue() {
        return yamlValue;
    }

    /**
     * Parses either the enum name (case-insensitive) or its kebab yaml alias.
     *
     * @throws IllegalArgumentException for null/blank/unknown input
     */
    public static SyncStrategy fromYaml(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SyncStrategy value must not be blank");
        }
        String normalized = value.trim();
        for (SyncStrategy s : values()) {
            if (s.yamlValue.equalsIgnoreCase(normalized)
                    || s.name().equalsIgnoreCase(normalized)
                    || s.name().equalsIgnoreCase(normalized.replace('-', '_'))) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown SyncStrategy: " + value
                + " (expected one of full-refresh / incremental-append / incremental-dedup / cdc)");
        // (note: switch on locale-normalised string handled above for robustness)
    }

    @SuppressWarnings("unused")
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
