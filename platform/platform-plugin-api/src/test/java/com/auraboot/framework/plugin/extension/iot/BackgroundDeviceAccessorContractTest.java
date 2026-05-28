package com.auraboot.framework.plugin.extension.iot;

import com.auraboot.framework.plugin.extension.iot.BackgroundDeviceAccessor.DeviceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link BackgroundDeviceAccessor} using an in-memory fake.
 * Verifies that any conforming implementation returns {@link Optional#empty()}
 * for unknown devices, isolates lookups by tenant, and exposes the immutable
 * {@link DeviceView} surface.
 */
class BackgroundDeviceAccessorContractTest {

    private InMemoryDeviceAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = new InMemoryDeviceAccessor();
        accessor.put(new DeviceView(
                "iot-A-001", "sensor-A", "temp-product",
                100L, "ONLINE", "/sys/temp-product/sensor-A/#",
                Map.of("zone", "north"), Instant.parse("2026-05-28T00:00:00Z")));
        accessor.put(new DeviceView(
                "iot-B-002", "sensor-A", "temp-product",
                200L, "OFFLINE", "/sys/temp-product/sensor-A/#",
                Map.of(), null));
    }

    @Test
    void lookupByCode_returnsTenantScopedDevice() {
        Optional<DeviceView> result = accessor.lookupByCode(100L, "sensor-A");

        assertThat(result).isPresent();
        assertThat(result.get().iotId()).isEqualTo("iot-A-001");
        assertThat(result.get().tenantId()).isEqualTo(100L);
        assertThat(result.get().status()).isEqualTo("ONLINE");
    }

    @Test
    void lookupByCode_isolatesAcrossTenants() {
        // Same deviceCode "sensor-A" exists in tenant 100 and tenant 200.
        DeviceView t100 = accessor.lookupByCode(100L, "sensor-A").orElseThrow();
        DeviceView t200 = accessor.lookupByCode(200L, "sensor-A").orElseThrow();

        assertThat(t100.iotId()).isEqualTo("iot-A-001");
        assertThat(t200.iotId()).isEqualTo("iot-B-002");
        assertThat(t100.iotId()).isNotEqualTo(t200.iotId());
    }

    @Test
    void lookupByCode_unknownReturnsEmpty() {
        assertThat(accessor.lookupByCode(100L, "missing")).isEmpty();
        assertThat(accessor.lookupByCode(999L, "sensor-A")).isEmpty();
    }

    @Test
    void lookupByIotId_isTenantAgnosticButCarriesTenantId() {
        Optional<DeviceView> result = accessor.lookupByIotId("iot-B-002");

        assertThat(result).isPresent();
        assertThat(result.get().tenantId()).isEqualTo(200L);
        assertThat(result.get().lastSeenAt()).isNull();
    }

    @Test
    void lookupByIotId_unknownReturnsEmpty() {
        assertThat(accessor.lookupByIotId("does-not-exist")).isEmpty();
    }

    @Test
    void deviceView_exposesImmutableFields() {
        DeviceView view = accessor.lookupByCode(100L, "sensor-A").orElseThrow();

        assertThat(view.iotId()).isEqualTo("iot-A-001");
        assertThat(view.deviceCode()).isEqualTo("sensor-A");
        assertThat(view.productKey()).isEqualTo("temp-product");
        assertThat(view.aclPattern()).isEqualTo("/sys/temp-product/sensor-A/#");
        assertThat(view.tags()).containsEntry("zone", "north");
        assertThat(view.lastSeenAt()).isEqualTo(Instant.parse("2026-05-28T00:00:00Z"));
    }

    /** Minimal in-memory implementation used to assert the contract shape. */
    static final class InMemoryDeviceAccessor implements BackgroundDeviceAccessor {
        private final Map<String, DeviceView> byIotId = new ConcurrentHashMap<>();
        private final Map<String, DeviceView> byTenantCode = new HashMap<>();

        void put(DeviceView view) {
            byIotId.put(view.iotId(), view);
            byTenantCode.put(view.tenantId() + ":" + view.deviceCode(), view);
        }

        @Override
        public Optional<DeviceView> lookupByCode(long tenantId, String deviceCode) {
            return Optional.ofNullable(byTenantCode.get(tenantId + ":" + deviceCode));
        }

        @Override
        public Optional<DeviceView> lookupByIotId(String iotId) {
            return Optional.ofNullable(byIotId.get(iotId));
        }
    }
}
