package com.auraboot.framework.plugin.extension.iot;

import java.time.Instant;
import java.util.List;

/**
 * Time-series read / write bridge for IoT plugin background components
 * (rule-worker, alarm router, telemetry projection job) that need to land or
 * query device data without coupling to a specific TSDB driver type.
 *
 * <p><b>Why this SPI was added late (M1.A.3 follow-up):</b> the original
 * M1.A drop ({@code b5927485}) shipped the four
 * {@code Background*Accessor} interfaces but missed the time-series port,
 * so plugin code that needed to write telemetry had to fall back to
 * private TDengine wiring. The M1.A.3 follow-up adds this fifth SPI under
 * the same package + null-fallback convention so plugins can finally land
 * device samples through a stable, vendor-agnostic contract.
 *
 * <p>Follows the same null-fallback SPI pattern as
 * {@link com.auraboot.framework.plugin.extension.BackgroundConnectorCredentialAccessor}
 * and the four sibling {@code Background*Accessor} interfaces:
 * a plugin {@code @Autowired(required = false)} field defaults to {@code null}
 * on older platforms (or when TDengine is not configured), and the plugin
 * treats {@code null} as &quot;time-series feature unavailable&quot; — typical
 * degradations are: skip telemetry persistence and emit a metric, or route
 * the batch to a dead-letter queue for later replay.
 *
 * <p><b>Tenant isolation (hard rule):</b> every read and write method takes
 * an explicit {@code long tenantId}. Implementations MUST apply this as a
 * SQL-level filter / connection-level routing key. The single-tenant
 * convenience overloads that M0's {@code com.auraboot.iot.tsport.TimeSeriesPort}
 * offered are intentionally NOT carried over — multi-tenant rigour comes
 * before ergonomics in the platform SPI.
 *
 * <p><b>Aggregation pushdown (hard rule):</b> range and aggregate queries
 * MUST translate to vendor-native SQL (e.g. TDengine
 * {@code INTERVAL(N) FILL(...)} or TimescaleDB
 * {@code time_bucket()}). Client-side aggregation is forbidden — at 100k+
 * samples per window it would blow the JVM heap.
 *
 * <p><b>No silent recovery (red line §8):</b> implementations MUST NOT
 * {@code catch (Exception)} around the TSDB driver and log-and-swallow.
 * Connection / query failures bubble up as runtime exceptions so the
 * caller (rule worker / telemetry consumer) can route the batch to its
 * own DLQ — exactly mirroring what the four
 * {@code Background*Accessor} SPIs do.
 *
 * @since 2.6.1
 */
public interface TimeSeriesPort {

    /**
     * Persist a batch of samples. Implementations choose between parameterised
     * batch insert, native binary protocol, or vendor-specific multi-row
     * {@code INSERT INTO ... VALUES ...} forms.
     *
     * <p>Empty input is a no-op (no SQL is executed, no metric is incremented).
     * Null input throws {@link NullPointerException}.
     *
     * <p>All points in the batch MUST belong to the supplied {@code tenantId};
     * implementations SHOULD NOT defensively re-check per-point but are free to
     * pin the connection / session to the tenant before issuing SQL.
     *
     * @param tenantId owning tenant (must be {@code &gt; 0})
     * @param points   non-null, possibly empty
     */
    void writeBatch(long tenantId, List<TimeSeriesPoint> points);

    /**
     * Return the most recent sample for each requested datapoint code on the
     * given device. Implementations SHOULD push this down to a single
     * vendor-native call (TDengine {@code LAST_ROW}, TimescaleDB
     * {@code DISTINCT ON}).
     *
     * <p>If a datapoint has no samples, it is silently omitted from the
     * result list — partial-empty results are not zero-padded; the caller
     * decides how to render gaps.
     *
     * @param tenantId   owning tenant (must be {@code &gt; 0})
     * @param deviceCode tenant-unique device code (not blank)
     * @param codes      datapoint codes; non-null, non-empty
     * @param limit      max samples per (deviceCode, code) tuple
     *                   ({@code &gt;= 1}); {@code 1} for the strict "latest" semantic
     * @return samples in ts-DESC order; empty list when none exist
     */
    List<TimeSeriesPoint> queryLatest(
            long tenantId, String deviceCode, List<String> codes, int limit);

    /**
     * Return raw samples within {@code [from, to)} ordered by ts ASC.
     *
     * <p>If {@link QueryParams.Range#downsample()} is non-null, the impl MUST
     * push the downsample down to a vendor-native windowed aggregation
     * (TDengine {@code INTERVAL(N)} with {@code FILL(LINEAR)} for gaps,
     * TimescaleDB {@code time_bucket_gapfill()}).
     *
     * @param tenantId owning tenant (must be {@code &gt; 0})
     * @param params   range parameters (non-null, validated by the record itself)
     * @return samples ordered by ts ASC; empty list when the range has no data
     */
    List<TimeSeriesPoint> queryRange(long tenantId, QueryParams.Range params);

    /**
     * Run an aggregation over fixed {@code groupBy} buckets in
     * {@code [from, to)}.
     *
     * @param tenantId owning tenant (must be {@code &gt; 0})
     * @param params   aggregate parameters (non-null, validated by the record itself)
     * @return one {@link AggregatedPoint} per non-empty bucket per (deviceCode,
     *         code) tuple, ordered by bucketStart ASC. Empty list when the
     *         range has no samples. Empty buckets are omitted, NOT
     *         zero-padded.
     */
    List<AggregatedPoint> queryAggregate(long tenantId, QueryParams.Aggregate params);
}
