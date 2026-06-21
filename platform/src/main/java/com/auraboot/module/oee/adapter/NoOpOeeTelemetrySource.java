package com.auraboot.module.oee.adapter;

import com.auraboot.module.oee.port.OeeTelemetrySource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Default no-op {@link OeeTelemetrySource}: OSS core has no GreptimeDB access, so it always returns
 * empty and the platform OEE engine uses the Postgres downtime-derived A/P/Q path. The IoT plugin
 * (ent-iot-control) contributes a {@code @Primary} GreptimeDB-backed implementation that overrides
 * this when present (Option A / GreptimeDB convergence, DDR-2026-06-21 D5).
 */
@Component
public class NoOpOeeTelemetrySource implements OeeTelemetrySource {

    @Override
    public Optional<OeeTelemetry> fetch(Long tenantId, String equipmentId, LocalDateTime start, LocalDateTime end) {
        return Optional.empty();
    }
}
