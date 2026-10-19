package com.auraboot.framework.observability;

/** Metric names shared by usage adapters and the platform observability endpoint. */
public final class LlmUsageMetrics {
    public static final String METRIC_WRITE_FAILURE = "auraboot_genai_ledger_write_failure_total";
    public static final String METRIC_UNPRICED = "auraboot_genai_unpriced_total";
    public static final String METRIC_CACHE_UNPRICED = "auraboot_genai_cache_unpriced_total";

    private LlmUsageMetrics() { }
}
