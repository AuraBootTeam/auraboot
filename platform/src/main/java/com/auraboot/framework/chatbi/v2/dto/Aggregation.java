package com.auraboot.framework.chatbi.v2.dto;

/**
 * Aggregation functions emittable by the LLM when overriding the metric's
 * default {@code agg}. PRD 17 §3.1.
 *
 * <p>The compiler currently does not surface per-token aggregation overrides
 * (the semantic layer's {@code MetricCompiler} owns aggregation choice via
 * {@code measure.agg}). This enum exists for forward compatibility (v0.2
 * may allow user-driven override like "average sales by region").
 */
public enum Aggregation {
    SUM,
    AVG,
    COUNT,
    MAX,
    MIN,
    COUNT_DISTINCT
}
