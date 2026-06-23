package com.auraboot.framework.plugin.extension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.pf4j.ExtensionPoint;

/**
 * Plugin {@link ExtensionPoint} for telemetry-derived OEE raw inputs (Option A / GreptimeDB
 * convergence, DDR-2026-06-21 D5). A plugin that owns time-series telemetry access (e.g. the IoT
 * control plugin reading the GreptimeDB {@code oee} table) contributes an {@code @Extension}; the
 * host discovers it via {@code AuraPluginManager.getExtensionsOfType(OeeTelemetrySourceExtension.class)}.
 *
 * <p>When no extension is registered (no telemetry plugin), the platform OEE engine falls back to
 * the Postgres downtime-derived A/P/Q path — i.e. unchanged legacy behavior.</p>
 *
 * <p>This is the established plugin-contribution pattern (cf. {@link RestEndpointExtension},
 * {@link CommandHandlerExtension}); a Spring {@code @Primary} bean in a PF4J plugin would NOT reach
 * the host context, so telemetry must arrive as an extension.</p>
 *
 * <p>Returns only JDK types (no platform-core DTOs) so this SPI module stays dependency-clean.</p>
 */
public interface OeeTelemetrySourceExtension extends ExtensionPoint {

    /**
     * Telemetry-measured raw inputs for an equipment over a window, or {@link Optional#empty()} when
     * no telemetry is available for it (the host then keeps the Postgres-derived A/P/Q).
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
