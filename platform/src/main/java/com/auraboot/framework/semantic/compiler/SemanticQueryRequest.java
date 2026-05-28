package com.auraboot.framework.semantic.compiler;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Inbound shape for {@code POST /api/semantic/query}. PRD 16 §6.1.
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@link #metrics} entries are metric {@code code} values (model qualification
 *       handled at the API layer by stripping the {@code <model>.} prefix before
 *       calling the compiler).</li>
 *   <li>{@link #dimensions} entries may include a grain suffix like
 *       {@code order_date__month}. The compiler strips it and applies
 *       {@code DATE_TRUNC(grain, col)}.</li>
 *   <li>{@link #filters} are conjunctive ({@code AND}). Disjunctions are not
 *       supported in v0.1.</li>
 *   <li>{@link #limit} 0 means "no LIMIT clause"; the API layer should default
 *       a sensible cap.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
public class SemanticQueryRequest {

    private List<String> metrics = new ArrayList<>();
    private List<String> dimensions = new ArrayList<>();
    private List<Filter> filters = new ArrayList<>();
    private TimeRange timeRange;
    private List<OrderBy> order = new ArrayList<>();
    private int limit;
    private int offset;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Filter {
        /** Dimension code (or metric code for HAVING — v0.2). */
        private String field;
        /** Operator: {@code eq, ne, gt, gte, lt, lte, in, not_in, like}. */
        private String op;
        /** Scalar for binary ops, {@link List} for {@code in/not_in}. */
        private Object value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeRange {
        /** Dimension code whose field_ref is the time column. */
        private String field;
        /** One of: {@code ytd, mtd, qtd, last_7_days, last_30_days, last_month, custom}. */
        private String preset;
        /** ISO-8601 date string, required iff preset = custom. */
        private String from;
        /** ISO-8601 date string, required iff preset = custom. */
        private String to;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderBy {
        private String field;
        /** {@code asc} or {@code desc}. */
        private String dir;
    }
}
