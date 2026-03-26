package com.auraboot.framework.meta.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for aggregate query results.
 * Contains the aggregated data rows, summary statistics, and query metadata.
 */
@Data
public class AggregateQueryResponse {

    /**
     * List of aggregated data rows, each row is a map of field name to value.
     */
    private List<Map<String, Object>> rows;

    /**
     * Summary statistics for the entire result set (e.g., grand totals).
     */
    private Map<String, Object> summary;

    /**
     * Metadata about the query that produced these results.
     */
    private QueryMeta meta;

    /**
     * Metadata describing the query structure.
     */
    @Data
    public static class QueryMeta {

        /**
         * List of dimension fields used for grouping.
         */
        private List<String> dimensions;

        /**
         * List of metric fields/aliases in the result.
         */
        private List<String> metrics;

        /**
         * The drill-down path taken to reach this result (for drill-down queries).
         */
        private List<String> drillPath;
    }
}
