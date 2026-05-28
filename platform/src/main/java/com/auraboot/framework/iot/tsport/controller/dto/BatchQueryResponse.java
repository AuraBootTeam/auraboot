package com.auraboot.framework.iot.tsport.controller.dto;

import com.auraboot.framework.plugin.extension.iot.AggregatedPoint;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import java.util.List;

/**
 * REST response body for {@code POST /iot/api/v1/timeseries:batchQuery}.
 *
 * <p>Each result entry's index aligns with the request {@code queries} list
 * 1:1 — callers can render in input order without re-keying.
 *
 * <p>One failed query does NOT short-circuit the batch; the corresponding
 * {@link Result} carries {@code errorCode} / {@code errorMessage} and the
 * other queries still return successfully (partial-success batch is
 * intentional for dashboard UX).
 *
 * @since 2.6.1
 */
public record BatchQueryResponse(List<Result> results) {

    /**
     * Per-query result. Exactly one of {@code points} / {@code buckets} /
     * {@code errorCode} will be non-null.
     *
     * <p>{@code type} echoes the corresponding {@link BatchQueryRequest.Query#type()}
     * so clients can branch on render mode without re-reading the request.
     */
    public record Result(
            BatchQueryRequest.QueryType type,
            String deviceCode,
            List<TimeSeriesPoint> points,
            List<AggregatedPoint> buckets,
            String errorCode,
            String errorMessage) {}
}
