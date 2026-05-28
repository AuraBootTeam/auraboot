package com.auraboot.framework.plugin.extension.iot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reference in-memory {@link TimeSeriesPort} used by
 * {@link TimeSeriesPortContractTest} so the contract base is self-validating
 * in the {@code platform-plugin-api} unit-test build (no Spring, no TDengine).
 *
 * <p>Multi-tenant: storage key is {@code (tenantId, deviceCode, code)}. The
 * fake enforces tenant isolation strictly — reads from a different tenant
 * always return empty, which lets the contract test prove the SPI's
 * isolation invariant without spinning a real DB.
 *
 * <p>NOT thread-safe and NOT production-grade — test fixture only.
 */
final class InMemoryTimeSeriesPort implements TimeSeriesPort {

    private record Key(long tenantId, String deviceCode, String code) {}

    private final Map<Key, List<TimeSeriesPoint>> store = new HashMap<>();

    @Override
    public void writeBatch(long tenantId, List<TimeSeriesPoint> points) {
        if (points == null) {
            throw new NullPointerException("points");
        }
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        for (TimeSeriesPoint p : points) {
            Key k = new Key(tenantId, p.deviceCode(), p.code());
            store.computeIfAbsent(k, ignored -> new ArrayList<>()).add(p);
        }
        // Keep each (tenant, device, code) bucket ts-ASC sorted so range
        // queries are O(n) without re-sorting per call.
        for (List<TimeSeriesPoint> bucket : store.values()) {
            bucket.sort(Comparator.comparing(TimeSeriesPoint::ts));
        }
    }

    @Override
    public List<TimeSeriesPoint> queryLatest(
            long tenantId, String deviceCode, List<String> codes, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        List<TimeSeriesPoint> out = new ArrayList<>();
        for (String code : codes) {
            List<TimeSeriesPoint> bucket = store.get(new Key(tenantId, deviceCode, code));
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            // ts-DESC, capped at limit.
            int n = Math.min(limit, bucket.size());
            for (int i = bucket.size() - 1; i >= bucket.size() - n; i--) {
                out.add(bucket.get(i));
            }
        }
        return out;
    }

    @Override
    public List<TimeSeriesPoint> queryRange(long tenantId, QueryParams.Range params) {
        List<TimeSeriesPoint> raw = new ArrayList<>();
        for (String code : params.codes()) {
            List<TimeSeriesPoint> bucket =
                    store.get(new Key(tenantId, params.deviceCode(), code));
            if (bucket == null) continue;
            for (TimeSeriesPoint p : bucket) {
                if (!p.ts().isBefore(params.from()) && p.ts().isBefore(params.to())) {
                    raw.add(p);
                }
            }
        }
        raw.sort(Comparator.comparing(TimeSeriesPoint::ts));
        // downsample is honoured by collapsing into per-window first sample;
        // the production TDengine impl pushes this to INTERVAL() — the fake
        // just proves the contract that downsample!=null returns ≤ raw count.
        if (params.downsample() == null) {
            return raw;
        }
        long bucketMs = params.downsample().toMillis();
        Map<Long, TimeSeriesPoint> firstPerBucket = new java.util.LinkedHashMap<>();
        for (TimeSeriesPoint p : raw) {
            long bucket = (p.ts().toEpochMilli() / bucketMs) * bucketMs;
            firstPerBucket.putIfAbsent(bucket, p);
        }
        return new ArrayList<>(firstPerBucket.values());
    }

    @Override
    public List<AggregatedPoint> queryAggregate(long tenantId, QueryParams.Aggregate params) {
        long bucketMs = params.groupBy().toMillis();
        List<AggregatedPoint> result = new ArrayList<>();
        for (String code : params.codes()) {
            List<TimeSeriesPoint> bucket =
                    store.get(new Key(tenantId, params.deviceCode(), code));
            if (bucket == null) continue;
            // Group raw samples in [from, to) into bucketStart buckets.
            Map<Long, List<TimeSeriesPoint>> grouped = new java.util.LinkedHashMap<>();
            for (TimeSeriesPoint p : bucket) {
                if (p.ts().isBefore(params.from()) || !p.ts().isBefore(params.to())) continue;
                long bs = (p.ts().toEpochMilli() / bucketMs) * bucketMs;
                grouped.computeIfAbsent(bs, ignored -> new ArrayList<>()).add(p);
            }
            for (Map.Entry<Long, List<TimeSeriesPoint>> e : grouped.entrySet()) {
                List<TimeSeriesPoint> pts = e.getValue();
                Number v = aggregate(params.aggregation(), pts);
                result.add(
                        new AggregatedPoint(
                                params.deviceCode(),
                                code,
                                Instant.ofEpochMilli(e.getKey()),
                                v,
                                (long) pts.size()));
            }
        }
        result.sort(Comparator.comparing(AggregatedPoint::bucketStart));
        return result;
    }

    private static Number aggregate(
            QueryParams.Aggregation agg, List<TimeSeriesPoint> pts) {
        return switch (agg) {
            case AVG -> pts.stream()
                    .mapToDouble(p -> p.value().doubleValue())
                    .average()
                    .orElse(0.0);
            case MIN -> pts.stream().mapToDouble(p -> p.value().doubleValue()).min().orElse(0.0);
            case MAX -> pts.stream().mapToDouble(p -> p.value().doubleValue()).max().orElse(0.0);
            case SUM -> pts.stream().mapToDouble(p -> p.value().doubleValue()).sum();
            case COUNT -> (long) pts.size();
            case FIRST -> pts.get(0).value();
            case LAST -> pts.get(pts.size() - 1).value();
        };
    }

    /** Test helper — collapses everything to a flat List for assertion convenience. */
    List<TimeSeriesPoint> dumpAll() {
        return store.values().stream().flatMap(List::stream).collect(Collectors.toList());
    }
}
