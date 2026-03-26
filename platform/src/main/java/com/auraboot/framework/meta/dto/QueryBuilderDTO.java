package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Request DTO for the query builder endpoint.
 * Accepts a structured query definition and generates safe SQL without accepting raw SQL.
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
public class QueryBuilderDTO {

    /**
     * The model code to query. Must refer to an existing model.
     * Required.
     */
    @NotBlank(message = "modelCode is required")
    private String modelCode;

    /**
     * Field codes to include in the SELECT clause.
     * If null or empty, all fields are selected.
     */
    private List<String> fields;

    /**
     * Filter conditions for the WHERE clause.
     */
    private List<FilterCondition> filters;

    /**
     * Field codes to group by.
     */
    private List<String> groupBy;

    /**
     * Aggregation functions to apply.
     */
    private List<AggregationConfig> aggregations;

    /**
     * Field code to sort by.
     */
    private String sortField;

    /**
     * Sort direction: ASC or DESC. Defaults to ASC.
     */
    private String sortOrder;

    /**
     * Maximum number of rows to return. Defaults to 500, capped at 5000.
     */
    @Min(value = 1, message = "limit must be at least 1")
    @Max(value = 5000, message = "limit must not exceed 5000")
    private Integer limit = 500;

    // ==================== Nested types ====================

    /**
     * A single filter condition applied to a field.
     */
    @Data
    public static class FilterCondition {

        /**
         * The field code to filter on (resolved to column name via model registry).
         */
        private String fieldName;

        /**
         * Comparison operator.
         * Supported: EQ, NEQ, GT, GTE, LT, LTE, LIKE, IN, NOT_IN, IS_NULL, IS_NOT_NULL
         */
        private String operator;

        /**
         * The value to compare against.
         * For IN / NOT_IN operators, provide a List.
         */
        private Object value;
    }

    /**
     * An aggregation to apply to a field.
     */
    @Data
    public static class AggregationConfig {

        /**
         * The field code to aggregate.
         */
        private String fieldCode;

        /**
         * Aggregation function: COUNT, SUM, AVG, MIN, MAX.
         */
        private String function;

        /**
         * Output alias for this aggregation in the result set.
         */
        private String alias;
    }
}
