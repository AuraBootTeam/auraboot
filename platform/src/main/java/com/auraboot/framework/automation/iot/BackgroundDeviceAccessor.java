package com.auraboot.framework.automation.iot;

import java.util.Map;

/**
 * Optional SPI implemented by the IoT plugin (e.g. {@code ent-iot-control}) to
 * surface device metadata to the automation enrichment node without making the
 * automation package depend on the plugin.
 *
 * <p>Mirrors the {@code BackgroundTenantAccessor} / {@code BackgroundDeviceAccessor}
 * pattern used by the crawler and connector SPIs (memory: 3-SPI pattern locked
 * 2026-05-27). If no bean is registered, enrichment is a no-op for the device
 * dimension; the rule still runs and may fall back to whatever device fields
 * the trigger payload already carried.
 */
public interface BackgroundDeviceAccessor {

    /**
     * @param deviceId stable IoT device identifier (typically ULID, but the SPI
     *                 deliberately leaves the format opaque)
     * @return arbitrary metadata map (site / model / firmware / location / ...);
     *         {@code null} or empty when the device is unknown to the IoT plane
     */
    Map<String, Object> findDeviceMetadata(String deviceId);
}
