package com.auraboot.framework.iot.spi.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.ProductSchema;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.ProductView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IotProductAccessorImplTest {

    private DynamicDataService dds;
    private IotProductAccessorImpl accessor;

    @BeforeEach
    void setUp() {
        dds = mock(DynamicDataService.class);
        accessor = new IotProductAccessorImpl(dds, new ObjectMapper());
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

    private static Map<String, Object> productRow() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tenant_id", 1L);
        r.put(IotProductAccessorImpl.COL_P_KEY, "pk-air");
        r.put(IotProductAccessorImpl.COL_P_NAME, Map.of("zh-CN", "空调", "en-US", "AC"));
        r.put(IotProductAccessorImpl.COL_P_NODE_TYPE, "DEVICE");
        r.put(IotProductAccessorImpl.COL_P_DATA_FORMAT, "JSON");
        r.put(IotProductAccessorImpl.COL_P_TRANSPORT_TYPE, "MQTT");
        return r;
    }

    private static Map<String, Object> dataPointRow(String code, String dataType) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put(IotProductAccessorImpl.COL_DP_PK, "pk-air");
        r.put(IotProductAccessorImpl.COL_DP_CODE, code);
        r.put(IotProductAccessorImpl.COL_DP_DATA_TYPE, dataType);
        r.put(IotProductAccessorImpl.COL_DP_REQUIRED, true);
        r.put(IotProductAccessorImpl.COL_DP_UNIT, "°C");
        r.put(IotProductAccessorImpl.COL_DP_VALUE_MIN, 0);
        r.put(IotProductAccessorImpl.COL_DP_VALUE_MAX, 100);
        return r;
    }

    @Test
    void lookupByKey_returnsProductView() {
        when(dds.list(eq("iot_product"), any(DynamicQueryRequest.class)))
                .thenReturn(page(productRow()));
        Optional<ProductView> got = accessor.lookupByKey(1L, "pk-air");
        assertThat(got).isPresent();
        assertThat(got.get().productKey()).isEqualTo("pk-air");
        assertThat(got.get().name()).containsEntry("zh-CN", "空调").containsEntry("en-US", "AC");
        assertThat(got.get().nodeType()).isEqualTo("DEVICE");
        assertThat(got.get().transportType()).isEqualTo("MQTT");
    }

    @Test
    void lookupByKey_rejectsInvalidInput() {
        assertThat(accessor.lookupByKey(0L, "pk")).isEmpty();
        assertThat(accessor.lookupByKey(1L, null)).isEmpty();
        assertThat(accessor.lookupByKey(1L, "")).isEmpty();
    }

    @Test
    void lookupByKey_emptyWhenRowMissing() {
        when(dds.list(eq("iot_product"), any(DynamicQueryRequest.class))).thenReturn(page());
        assertThat(accessor.lookupByKey(1L, "missing")).isEmpty();
    }

    @Test
    void getSchema_returnsEmpty_whenNoRowsOnAnyTable() {
        when(dds.list(eq("iot_data_point"), any(DynamicQueryRequest.class))).thenReturn(page());
        when(dds.list(eq("iot_device_event"), any(DynamicQueryRequest.class))).thenReturn(page());
        when(dds.list(eq("iot_device_service"), any(DynamicQueryRequest.class))).thenReturn(page());
        assertThat(accessor.getSchema("pk-air")).isEmpty();
    }

    @Test
    void getSchema_assemblesProperties_andEmptyEventsServices() {
        when(dds.list(eq("iot_data_point"), any(DynamicQueryRequest.class)))
                .thenReturn(page(dataPointRow("temp", "float"), dataPointRow("hum", "int")));
        when(dds.list(eq("iot_device_event"), any(DynamicQueryRequest.class))).thenReturn(page());
        when(dds.list(eq("iot_device_service"), any(DynamicQueryRequest.class))).thenReturn(page());

        Optional<ProductSchema> got = accessor.getSchema("pk-air");
        assertThat(got).isPresent();
        ProductSchema s = got.get();
        assertThat(s.properties()).hasSize(2);
        assertThat(s.properties().get(0).identifier()).isEqualTo("temp");
        assertThat(s.properties().get(0).dataType()).isEqualTo("float");
        assertThat(s.properties().get(0).required()).isTrue();
        assertThat(s.properties().get(0).unit()).isEqualTo("°C");
        assertThat(s.properties().get(0).range())
                .containsEntry("min", 0).containsEntry("max", 100);
        assertThat(s.events()).isEmpty();
        assertThat(s.services()).isEmpty();
    }

    @Test
    void getSchema_blankProductKey_returnsEmpty() {
        assertThat(accessor.getSchema(null)).isEmpty();
        assertThat(accessor.getSchema("")).isEmpty();
    }

    @Test
    void lookupByKey_handlesLocalizedNameAsJsonString() {
        Map<String, Object> row = productRow();
        row.put(IotProductAccessorImpl.COL_P_NAME, "{\"zh-CN\":\"空调\",\"en-US\":\"AC\"}");
        when(dds.list(eq("iot_product"), any(DynamicQueryRequest.class))).thenReturn(page(row));
        assertThat(accessor.lookupByKey(1L, "pk-air").get().name())
                .containsEntry("zh-CN", "空调").containsEntry("en-US", "AC");
    }
}
