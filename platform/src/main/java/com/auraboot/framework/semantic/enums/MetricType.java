package com.auraboot.framework.semantic.enums;

/**
 * The 5 metric types supported by v0.1.
 *
 * <p>Each type has a specific {@code type_params} shape, validated by JSON Schema
 * {@code semantic-v0.1.schema.json} {@code $defs.*TypeParams}.
 */
public enum MetricType {
    /** {@code type_params: { measure: <code> }} — SUM/COUNT/AVG/MAX/MIN/COUNT_DISTINCT over a single measure. */
    SIMPLE,
    /** {@code type_params: { numerator, denominator }} — measure / measure. */
    RATIO,
    /** {@code type_params: { measure, window: ytd|mtd|qtd|running }} — window function over primary_time. */
    CUMULATIVE,
    /** {@code type_params: { expr: "{metric_a} / {metric_b}" }} — expression composed of other metrics. */
    DERIVED,
    /** {@code type_params: { base_measure, conversion_measure, entity, window: \d+[dhwmy] }} — conversion within window. */
    CONVERSION;

    public String yamlValue() {
        return name().toLowerCase();
    }

    public static MetricType fromYaml(String s) {
        return MetricType.valueOf(s.toUpperCase());
    }
}
