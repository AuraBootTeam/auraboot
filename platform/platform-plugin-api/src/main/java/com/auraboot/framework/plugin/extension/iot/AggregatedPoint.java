package com.auraboot.framework.plugin.extension.iot;

import java.time.Instant;
import java.util.Objects;

/**
 * One aggregation bucket result on
 * {@link TimeSeriesPort#queryAggregate}.
 *
 * <p>{@code bucketStart} is the inclusive lower edge of the bucket; the
 * width is whatever {@link QueryParams.Aggregate#groupBy()} was passed.
 *
 * <p>{@code value} is {@link Number} to preserve int / bool semantics for
 * {@code COUNT} (long) vs {@code AVG} (double); see
 * {@link TimeSeriesPoint} javadoc for the same rationale.
 *
 * <p>{@code pointCount} is the number of raw samples that contributed to
 * the bucket. {@code null} means the impl did not provide a count
 * (acceptable, but discouraged — UIs use it for confidence rendering).
 *
 * @since 2.6.1
 */
public record AggregatedPoint(
        String deviceCode,
        String code,
        Instant bucketStart,
        Number value,
        Long pointCount) {

    public AggregatedPoint {
        Objects.requireNonNull(deviceCode, "deviceCode");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(bucketStart, "bucketStart");
        Objects.requireNonNull(value, "value");
    }
}
