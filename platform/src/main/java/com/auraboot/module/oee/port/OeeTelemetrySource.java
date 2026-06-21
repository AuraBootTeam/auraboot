package com.auraboot.module.oee.port;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Optional SPI for telemetry-derived OEE raw inputs (Option A / GreptimeDB convergence,
 * DDR-2026-06-21 D5). Implemented by the IoT side (which owns GreptimeDB access); the OSS core
 * ships a no-op default ({@link com.auraboot.module.oee.adapter.NoOpOeeTelemetrySource}) so the
 * platform engine falls back to the Postgres downtime-derived A/P/Q path when no telemetry source
 * is present.
 *
 * <p>Single abstract method -> functional interface (lambda-friendly in tests).</p>
 */
public interface OeeTelemetrySource {

    /**
     * Telemetry-measured raw inputs for an equipment over a window, or {@link Optional#empty()} when
     * no telemetry is available for it.
     *
     * @param tenantId    tenant id
     * @param equipmentId equipment primary key (pid / ULID string)
     * @param start       window start (inclusive)
     * @param end         window end (exclusive)
     */
    Optional<OeeTelemetry> fetch(Long tenantId, String equipmentId, LocalDateTime start, LocalDateTime end);

    /**
     * Telemetry-derived raw inputs: operatingHours = run-time (running-signal TWA, hours);
     * outputQty = produced count (counter delta, pieces); goodQty = good count (pieces).
     */
    record OeeTelemetry(BigDecimal operatingHours, BigDecimal outputQty, BigDecimal goodQty) {
    }
}
