package com.auraboot.framework.iot.spi.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.iot.BackgroundDeviceAccessor.DeviceView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IotDeviceAccessorImplTest {

    private DynamicDataService dds;
    private IotDeviceAccessorImpl accessor;

    @BeforeEach
    void setUp() {
        dds = mock(DynamicDataService.class);
        accessor = new IotDeviceAccessorImpl(dds, new ObjectMapper());
        MetaContext.clear();
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private static PaginationResult<Map<String, Object>> page(Map<String, Object>... rows) {
        PaginationResult<Map<String, Object>> p = new PaginationResult<>();
        p.setRecords(List.of(rows));
        return p;
    }

    private static Map<String, Object> deviceRow(long tenantId, String code, String iotId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tenant_id", tenantId);
        r.put(IotDeviceAccessorImpl.COL_DEVICE_CODE, code);
        r.put(IotDeviceAccessorImpl.COL_IOT_ID, iotId);
        r.put(IotDeviceAccessorImpl.COL_PRODUCT_KEY, "pk-air");
        r.put(IotDeviceAccessorImpl.COL_STATUS, "ONLINE");
        r.put(IotDeviceAccessorImpl.COL_ACL_PATTERN, "/sys/pk-air/" + code + "/#");
        r.put(IotDeviceAccessorImpl.COL_TAGS, Map.of("zone", "A1", "model", "x86"));
        r.put(IotDeviceAccessorImpl.COL_LAST_SEEN_AT, Instant.parse("2026-05-28T09:00:00Z"));
        return r;
    }

    @Test
    void lookupByCode_returnsView_andBindsTenantContextDuringCall() {
        when(dds.list(eq("iot_device"), any(DynamicQueryRequest.class)))
                .thenAnswer(inv -> {
                    assertThat(MetaContext.getCurrentTenantId()).isEqualTo(42L);
                    return page(deviceRow(42L, "dev-1", "iot-ULID-1"));
                });

        Optional<DeviceView> got = accessor.lookupByCode(42L, "dev-1");

        assertThat(got).isPresent();
        DeviceView v = got.get();
        assertThat(v.iotId()).isEqualTo("iot-ULID-1");
        assertThat(v.deviceCode()).isEqualTo("dev-1");
        assertThat(v.productKey()).isEqualTo("pk-air");
        assertThat(v.tenantId()).isEqualTo(42L);
        assertThat(v.status()).isEqualTo("ONLINE");
        assertThat(v.tags()).containsEntry("zone", "A1").containsEntry("model", "x86");
        assertThat(v.lastSeenAt()).isEqualTo(Instant.parse("2026-05-28T09:00:00Z"));
        assertThat(MetaContext.exists()).isFalse();
    }

    @Test
    void lookupByCode_rejectsBlankOrZero() {
        assertThat(accessor.lookupByCode(0L, "x")).isEmpty();
        assertThat(accessor.lookupByCode(-1L, "x")).isEmpty();
        assertThat(accessor.lookupByCode(1L, null)).isEmpty();
        assertThat(accessor.lookupByCode(1L, "")).isEmpty();
        assertThat(accessor.lookupByCode(1L, "  ")).isEmpty();
    }

    @Test
    void lookupByCode_returnsEmpty_whenNoRows() {
        when(dds.list(eq("iot_device"), any(DynamicQueryRequest.class))).thenReturn(page());
        assertThat(accessor.lookupByCode(1L, "missing")).isEmpty();
    }

    @Test
    void lookupByCode_refusesCrossTenant_evenIfRowLeaks() {
        // Defensive: if the dynamic-data filter chain misbehaves and returns
        // a row for a different tenant, the accessor MUST refuse to leak.
        when(dds.list(eq("iot_device"), any(DynamicQueryRequest.class)))
                .thenReturn(page(deviceRow(99L, "dev-1", "iot-x")));
        assertThat(accessor.lookupByCode(42L, "dev-1")).isEmpty();
    }

    @Test
    void lookupByCode_returnsEmpty_whenDdsThrows_doesNotPropagate() {
        when(dds.list(eq("iot_device"), any(DynamicQueryRequest.class)))
                .thenThrow(new RuntimeException("DB down"));
        assertThat(accessor.lookupByCode(42L, "dev-1")).isEmpty();
        assertThat(MetaContext.exists()).isFalse();
    }

    @Test
    void lookupByIotId_returnsView_withRowTenantPreserved() {
        when(dds.list(eq("iot_device"), any(DynamicQueryRequest.class)))
                .thenReturn(page(deviceRow(7L, "dev-7", "iot-g")));
        Optional<DeviceView> got = accessor.lookupByIotId("iot-g");
        assertThat(got).isPresent();
        assertThat(got.get().tenantId()).isEqualTo(7L);
    }

    @Test
    void lookupByIotId_emptyOnBlank() {
        assertThat(accessor.lookupByIotId(null)).isEmpty();
        assertThat(accessor.lookupByIotId("")).isEmpty();
    }

    @Test
    void tags_parsedFromJsonString_whenMapNotPreDeserialised() {
        Map<String, Object> row = deviceRow(1L, "d", "i");
        row.put(IotDeviceAccessorImpl.COL_TAGS, "{\"k\":\"v\"}");
        when(dds.list(eq("iot_device"), any(DynamicQueryRequest.class))).thenReturn(page(row));
        assertThat(accessor.lookupByCode(1L, "d").get().tags()).containsEntry("k", "v");
    }

    @Test
    void tags_emptyOnMalformedJson() {
        Map<String, Object> row = deviceRow(1L, "d", "i");
        row.put(IotDeviceAccessorImpl.COL_TAGS, "not-json");
        when(dds.list(eq("iot_device"), any(DynamicQueryRequest.class))).thenReturn(page(row));
        assertThat(accessor.lookupByCode(1L, "d").get().tags()).isEmpty();
    }
}
