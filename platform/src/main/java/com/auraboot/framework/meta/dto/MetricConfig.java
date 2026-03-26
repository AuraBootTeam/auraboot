package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * Configuration for a single metric in aggregate queries.
 * Used by dashboard chart components to define what data to aggregate.
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
public class MetricConfig {

    /**
     * The field name to aggregate on.
     * Example: "amount", "quantity", "price"
     */
    private String field;

    /**
     * The aggregation function to apply.
     * Supported values: COUNT, COUNT_DISTINCT, SUM, AVG, MAX, MIN
     */
    private String aggregation;

    /**
     * Optional alias for the result column.
     * If not specified, a default alias will be generated.
     * Example: "totalAmount", "avgPrice"
     */
    private String alias;
}
