package com.auraboot.framework.iot.tsport.controller.dto;

import com.auraboot.framework.plugin.extension.iot.QueryParams;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * REST request body for {@code POST /iot/api/v1/timeseries:batchQuery}.
 *
 * <p>Each {@link Query} entry maps 1:1 to one of the three single-query
 * endpoints. We re-validate at controller-edge level (deviceCode +
 * codes presence) and let the {@link QueryParams} records re-validate
 * range bounds when {@code type=RANGE|AGGREGATE}.
 *
 * <p>Limits are intentionally low (≤ 50 queries per batch) — this is a
 * UX convenience for dashboards rendering many widgets, not a bulk-export
 * channel. Bulk export is a separate M2+ feature.
 *
 * @since 2.6.1
 */
public record BatchQueryRequest(List<Query> queries) {

    public BatchQueryRequest {
        Objects.requireNonNull(queries, "queries");
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("queries must not be empty");
        }
        if (queries.size() > 50) {
            throw new IllegalArgumentException("queries must not exceed 50 per batch");
        }
        queries = List.copyOf(queries);
    }

    public enum QueryType {
        LATEST,
        RANGE,
        AGGREGATE
    }

    /**
     * One batch entry. Fields are filtered by {@code type}:
     * <ul>
     *   <li>{@code LATEST}: uses {@code deviceCode, codes, limit}</li>
     *   <li>{@code RANGE}: uses {@code deviceCode, codes, from, to, downsample}</li>
     *   <li>{@code AGGREGATE}: uses {@code deviceCode, codes, from, to, aggregation, groupBy}</li>
     * </ul>
     */
    public record Query(
            QueryType type,
            String deviceCode,
            List<String> codes,
            Integer limit,
            Instant from,
            Instant to,
            String downsample,
            QueryParams.Aggregation aggregation,
            String groupBy) {

        public Query {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(deviceCode, "deviceCode");
            Objects.requireNonNull(codes, "codes");
            if (deviceCode.isBlank()) {
                throw new IllegalArgumentException("deviceCode must not be blank");
            }
            if (codes.isEmpty()) {
                throw new IllegalArgumentException("codes must not be empty");
            }
            codes = List.copyOf(codes);
        }
    }
}
