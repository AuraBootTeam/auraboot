package com.auraboot.framework.meta.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for aggregate queries from dashboard chart components.
 * Supports both dynamic aggregate queries and named queries.
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
public class AggregateQueryRequest {

    /**
     * Query type: "aggregate" for dynamic aggregation, "namedQuery" for predefined queries.
     * Defaults to "aggregate".
     */
    private String type = "aggregate";

    /**
     * The model code to query data from.
     * Required for aggregate type queries.
     * Example: "order", "customer", "product"
     */
    private String modelCode;

    /**
     * Optional semantic-model code (PRD 16 §3) used to route the request through the
     * semantic layer rather than the raw model + SQL builder path.
     *
     * <p>When non-null the impl delegates to {@code SemanticQueryService.executeQuery}
     * and translates {@link #metrics} / {@link #dimensions} / {@link #filters} /
     * {@code drillFilters} into {@code SemanticQueryRequest} fields. Metric entries
     * must reference declared {@code semantic_model.metrics[].code} values
     * (qualified as {@code <semanticModelCode>.<metric_code>} accepted; bare codes
     * also accepted and qualified at the boundary).
     *
     * <p>When null the legacy path is preserved bit-identical for backward
     * compatibility with all existing dashboard / chart components.
     */
    private String semanticModelCode;

    /**
     * The named query code to execute.
     * Required for namedQuery type queries.
     */
    private String queryCode;

    /**
     * Dimension fields for grouping.
     * These fields will be used in GROUP BY clause.
     * Example: ["region", "category"]
     */
    private List<String> dimensions;

    /**
     * Metric configurations for aggregation.
     * Example: [{field: "amount", aggregation: "sum", alias: "totalAmount"}]
     */
    private List<MetricConfig> metrics;

    /**
     * Filter conditions for the query.
     */
    private List<FilterConfig> filters;

    /**
     * Explicit GROUP BY fields.
     * If not specified, dimensions will be used for grouping.
     */
    private List<String> groupBy;

    /**
     * Sort configuration for the results.
     */
    private List<OrderByConfig> orderBy;

    /**
     * Maximum number of records to return.
     */
    private Integer limit;

    /**
     * Additional filters applied during drill-down operations.
     * These are typically added when user clicks on a chart element.
     */
    private List<FilterConfig> drillFilters;

    /**
     * Parameters for named queries.
     * Key-value pairs that will be substituted in the query.
     */
    private Map<String, Object> parameters;

    /**
     * Filter configuration for query conditions.
     */
    @Data
    public static class FilterConfig {
        /**
         * The field name to filter on.
         */
        private String field;

        /**
         * The comparison operator.
         * Supported: eq, ne, gt, gte, lt, lte, in, notIn, like, between, isNull, isNotNull
         */
        private String operator;

        /**
         * The value to compare against.
         * For 'in' operator, this should be an array.
         * For 'between' operator, this should be an array with two elements [min, max].
         */
        private Object value;

        /**
         * Logical operator to combine with other filters.
         * Defaults to "and".
         */
        private String logic = "and";
    }

    /**
     * Sort configuration for ordering results.
     */
    @Data
    public static class OrderByConfig {
        /**
         * The field name or alias to sort by.
         */
        private String field;

        /**
         * Sort direction: "asc" or "desc".
         * Defaults to "asc".
         */
        private String direction = "asc";
    }
}
