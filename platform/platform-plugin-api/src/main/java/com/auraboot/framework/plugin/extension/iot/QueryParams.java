package com.auraboot.framework.plugin.extension.iot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Read-side parameter holders for {@link TimeSeriesPort}.
 *
 * <p>Kept as small, immutable records (rather than a single bag-of-options)
 * so the {@link TimeSeriesPort} method signatures stay self-documenting and
 * IDE / compiler catch type mismatches at the call site.
 *
 * @since 2.6.1
 */
public final class QueryParams {

    private QueryParams() {}

    /**
     * Range scan over {@code [from, to)}.
     *
     * <p>When {@link #downsample()} is non-null the impl pushes the
     * downsample to a vendor-native windowed aggregation
     * (TDengine {@code INTERVAL(N) FILL(LINEAR)}; TimescaleDB
     * {@code time_bucket_gapfill()}). When {@code null} the impl returns
     * every raw sample in the range.
     */
    public record Range(
            String deviceCode,
            List<String> codes,
            Instant from,
            Instant to,
            Duration downsample) {

        public Range {
            Objects.requireNonNull(deviceCode, "deviceCode");
            Objects.requireNonNull(codes, "codes");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (codes.isEmpty()) {
                throw new IllegalArgumentException("codes must not be empty");
            }
            if (!to.isAfter(from)) {
                throw new IllegalArgumentException("to must be strictly after from");
            }
            if (downsample != null && (downsample.isZero() || downsample.isNegative())) {
                throw new IllegalArgumentException("downsample must be > 0 when supplied");
            }
            codes = List.copyOf(codes);
        }
    }

    /**
     * Aggregate scan: applies {@link #aggregation} over fixed {@link #groupBy}
     * buckets in {@code [from, to)}.
     */
    public record Aggregate(
            String deviceCode,
            List<String> codes,
            Instant from,
            Instant to,
            Aggregation aggregation,
            Duration groupBy) {

        public Aggregate {
            Objects.requireNonNull(deviceCode, "deviceCode");
            Objects.requireNonNull(codes, "codes");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(aggregation, "aggregation");
            Objects.requireNonNull(groupBy, "groupBy");
            if (codes.isEmpty()) {
                throw new IllegalArgumentException("codes must not be empty");
            }
            if (!to.isAfter(from)) {
                throw new IllegalArgumentException("to must be strictly after from");
            }
            if (groupBy.isZero() || groupBy.isNegative()) {
                throw new IllegalArgumentException("groupBy must be > 0");
            }
            codes = List.copyOf(codes);
        }
    }

    /**
     * Supported aggregation operators — the superset every
     * {@link TimeSeriesPort} impl must honour.
     */
    public enum Aggregation {
        AVG,
        MAX,
        MIN,
        SUM,
        COUNT,
        FIRST,
        LAST
    }
}
