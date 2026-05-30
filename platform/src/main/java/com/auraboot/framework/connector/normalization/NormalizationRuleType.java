package com.auraboot.framework.connector.normalization;

/**
 * Enumeration of field-level normalization rule types supported by the
 * {@link InMemoryNormalizationEngine} (L3 data-cleansing layer, PRD 16 §semantic.yml).
 *
 * <p>Rules are declared in {@code *.normalize.yml} files and resolved at runtime
 * via {@link NormalizationConfig.FieldRule#type()}.
 *
 * @since 5.3.0
 */
public enum NormalizationRuleType {

    /**
     * Convert a timestamp string between {@code iso8601}, {@code epoch_millis},
     * and {@code epoch_seconds} representations.
     * <p>Required params: {@code from_format}, {@code to_format}.
     */
    TIMESTAMP,

    /**
     * Scale a numeric value between unit representations (e.g. dollars → cents,
     * percent → fraction). Supports arbitrary multiplier ratios via
     * {@code from} / {@code to} params or explicit {@code multiplier}.
     * <p>Required params: {@code from}, {@code to}.
     */
    NUMERIC_UNIT,

    /**
     * Map enumeration values from vendor-specific codes to canonical domain codes.
     * Values absent from the mapping are passed through unchanged.
     * <p>Required params: {@code mapping} (a nested key→value map).
     */
    ENUM_MAP,

    /**
     * Rename a source field to a different target key with no value transformation.
     * No additional params required.
     */
    RENAME
}
