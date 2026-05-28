package com.auraboot.framework.semantic.compiler;

import lombok.Getter;

/**
 * Thrown when a {@link SemanticQueryRequest} cannot be compiled into SQL.
 *
 * <p>Carries a stable {@link #errorCode} so the HTTP layer can map it to a
 * 400/403/500 response without sniffing the message.
 *
 * <p>Common codes (subset):
 * <ul>
 *   <li>{@code UNKNOWN_METRIC} — request references a metric code not in the model</li>
 *   <li>{@code UNKNOWN_DIMENSION} — request references a dimension code not in the model</li>
 *   <li>{@code UNKNOWN_MEASURE} — metric references a measure code not in the model</li>
 *   <li>{@code TIMERANGE_INVALID} — preset / from / to malformed</li>
 *   <li>{@code UNSUPPORTED_AGGREGATION} — measure.agg outside SUM/COUNT/AVG/MAX/MIN/COUNT_DISTINCT</li>
 *   <li>{@code UNSUPPORTED_METRIC_TYPE} — metric.type outside the 5 known types</li>
 *   <li>{@code DERIVED_PLACEHOLDER_UNRESOLVED} — derived expr references missing metric</li>
 *   <li>{@code MODEL_REF_MISSING} — DTO has no model_ref (table) for query</li>
 *   <li>{@code NO_PRIMARY_TIME} — cumulative metric requested but model has no primary_time dim</li>
 * </ul>
 */
@Getter
public class MetricCompileException extends RuntimeException {

    private final String errorCode;

    public MetricCompileException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public MetricCompileException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
