package com.auraboot.framework.plugin.extension.iot;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable single sample on the {@link TimeSeriesPort} contract.
 *
 * <p>{@code value} is {@link Number} (not {@code double}) so the same DTO
 * can carry integer counters / gauges / booleans without forcing every
 * consumer to round-trip through floating point. The reference TDengine
 * impl coerces to {@code DOUBLE} on write (single column today) — M2 will
 * introduce per-datapoint typed columns to preserve int / bool semantics.
 *
 * <p>{@code qualityCode} follows GB/T 33863 (industrial common quality
 * codes: {@code GOOD / UNCERTAIN / BAD / ...}); {@code null} means the
 * device did not report a quality flag. The platform deliberately uses
 * a {@code String} (not enum) so vertical-specific quality codes survive
 * unchanged.
 *
 * <p>The DTO does NOT carry {@code tenantId} — the routing tenant is
 * supplied at the {@link TimeSeriesPort} method level so a single batch
 * stays cheap to serialise and reason about. Cross-tenant points in one
 * batch are NOT supported and implementations MAY reject them.
 *
 * @since 2.6.1
 */
public record TimeSeriesPoint(
        String deviceCode, String code, Instant ts, Number value, String qualityCode) {

    public TimeSeriesPoint {
        Objects.requireNonNull(deviceCode, "deviceCode");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(value, "value");
    }
}
