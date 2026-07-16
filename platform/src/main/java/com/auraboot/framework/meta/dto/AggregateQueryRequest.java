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
     *
     * <p>A {@code FilterConfig} is one of two node kinds, forming a recursive boolean tree:
     * <ul>
     *   <li><b>Leaf</b> — has a {@link #field} (+ {@link #operator} + {@link #value}) and
     *       no {@link #children}. Produces a single predicate ({@code field op value}).</li>
     *   <li><b>Group</b> — has a non-empty {@link #children} list and no {@link #field}.
     *       Combines its children with the group's {@link #logic} ({@code "and"} / {@code "or"}),
     *       recursively. {@code field}/{@code operator}/{@code value} are ignored on a group.</li>
     * </ul>
     *
     * <p><b>Combination semantics:</b> the top-level {@code filters} / {@code drillFilters}
     * list elements are always combined with <b>AND</b> (backward compatible). To express OR
     * (or nested AND/OR), wrap conditions in a group node, e.g.
     * <pre>{@code {"logic":"or","children":[{"field":"region","operator":"eq","value":"East"},
     *                                      {"field":"region","operator":"eq","value":"West"}]}}</pre>
     * A leaf's own {@link #logic} value is not used — only a group's {@code logic} matters.
     */
    @Data
    public static class FilterConfig {
        /**
         * The field name to filter on. Required for a leaf node; must be {@code null} on a group node.
         */
        private String field;

        /**
         * The comparison operator (leaf only).
         * Supported: eq, ne/neq, gt, gte/ge, lt, lte/le, like, in, not_in, is_null, is_not_null,
         * and {@code relative} (relative-time range — see {@link #value}).
         */
        private String operator;

        /**
         * The value to compare against (leaf only).
         * <ul>
         *   <li>For {@code in} / {@code not_in}: an array.</li>
         *   <li>For {@code relative}: a relative-time token string
         *       ({@code today}, {@code yesterday}, {@code last_7_days}, {@code last_30_days},
         *       {@code this_week}, {@code this_month}, {@code this_quarter}, {@code this_year})
         *       or an object {@code {"relative":"last_n_days","n":30}}. The server resolves the
         *       token into a concrete half-open date range and binds the bounds as parameters.</li>
         * </ul>
         */
        private Object value;

        /**
         * Logical operator used by a <b>group</b> node to combine its {@link #children}
         * ({@code "and"} or {@code "or"}). Defaults to {@code "and"}. Ignored on a leaf node,
         * where combination is dictated by the parent group (top-level list is always AND).
         */
        private String logic = "and";

        /**
         * Child filters of a <b>group</b> node. When non-empty this {@code FilterConfig} is a
         * group and {@link #field}/{@link #operator}/{@link #value} are ignored; when
         * {@code null}/empty it is a leaf. Enables arbitrary nested AND/OR trees.
         */
        private List<FilterConfig> children;
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
