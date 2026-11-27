package com.auraboot.framework.plugin.extension.iot;

import com.auraboot.framework.plugin.extension.iot.BackgroundDeviceAccessor.DeviceView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shape-only pin of the {@link BackgroundDeviceAccessor.DeviceView} SPI
 * record, per testing-layering.md §2.1 (shape self-check exemption).
 *
 * Lookup semantics (tenant scoping, unknown returns empty, iotId vs
 * tenant+code keys) are persistence behavior: they are NOT asserted here.
 * They are owed to real-stack tests against the platform implementation
 * ({@code IotDeviceAccessorImpl}) — tracked in workspace
 * {@code tools/fake-db-registry.tsv}. The former in-memory contract fake
 * proved its own map filters, which proves nothing about any real store.
 */
class BackgroundDeviceAccessorShapeTest {

    @Test
    void deviceViewExposesItsImmutableSurface() {
        DeviceView view = new DeviceView(
                "pid-A-001", "iot-A-001", "sensor-A", "temp-product",
                100L, "ONLINE", "/sys/temp-product/sensor-A/#",
                Map.of("zone", "north", "model", "x86"),
                Instant.parse("2026-05-28T00:00:00Z"));

        assertThat(view.recordId()).isEqualTo("pid-A-001");
        assertThat(view.iotId()).isEqualTo("iot-A-001");
        assertThat(view.deviceCode()).isEqualTo("sensor-A");
        assertThat(view.productKey()).isEqualTo("temp-product");
        assertThat(view.tenantId()).isEqualTo(100L);
        assertThat(view.status()).isEqualTo("ONLINE");
        assertThat(view.aclPattern()).isEqualTo("/sys/temp-product/sensor-A/#");
        assertThat(view.tags()).containsEntry("zone", "north").containsEntry("model", "x86");
        assertThat(view.lastSeenAt()).isEqualTo(Instant.parse("2026-05-28T00:00:00Z"));
    }

    @Test
    void deviceViewToleratesNeverSeenDevicesAndEmptyTags() {
        DeviceView view = new DeviceView(
                "pid-B-002", "iot-B-002", "sensor-A", "temp-product",
                200L, "OFFLINE", "/sys/temp-product/sensor-A/#",
                Map.of(), null);

        assertThat(view.tags()).isEmpty();
        assertThat(view.lastSeenAt()).isNull();
    }
}
