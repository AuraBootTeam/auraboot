package com.auraboot.framework.semantic.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * BI usage rollup over {@code ab_semantic_query_log} (BI rectification R1).
 *
 * <p>The "effect" layer of the rectification plan: how much the governed
 * semantic pipeline is actually used, how fast it answers, and how often the
 * cache absorbs load — computed straight from the query log the pipeline
 * already writes.
 */
@Data
@Builder
public class SemanticUsageSummaryDTO {

    private int days;

    private long totalQueries;

    /** 95th percentile of server-side execution time in milliseconds. */
    private Double p95DurationMs;

    /** Fraction of queries served from cache, 0..1. */
    private Double cacheHitRate;

    private long activeUsers;

    private long totalRows;

    private List<DailyUsage> daily;

    @Data
    @Builder
    public static class DailyUsage {
        private String day;
        private long queries;
        private Double p95DurationMs;
    }
}
