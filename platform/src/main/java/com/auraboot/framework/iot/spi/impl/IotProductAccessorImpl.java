package com.auraboot.framework.iot.spi.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link BackgroundProductAccessor}: reads product header from
 * {@code iot_product} and assembles the TSL schema by joining
 * {@code iot_data_point}, {@code iot_device_event} and
 * {@code iot_device_service} on {@code iot_*_product_key}.
 *
 * @since 2.6.0
 */
@Slf4j
@Service
public class IotProductAccessorImpl implements BackgroundProductAccessor {

    static final String MODEL_PRODUCT = "iot_product";
    static final String MODEL_DATA_POINT = "iot_data_point";
    static final String MODEL_EVENT = "iot_device_event";
    static final String MODEL_SERVICE = "iot_device_service";

    static final String COL_P_KEY = "iot_p_product_key";
    static final String COL_P_NAME = "iot_p_name";
    static final String COL_P_NODE_TYPE = "iot_p_node_type";
    static final String COL_P_DATA_FORMAT = "iot_p_data_format";
    static final String COL_P_TRANSPORT_TYPE = "iot_p_transport_type";
    static final String COL_P_PROVISION_TYPE = "iot_p_provision_type";

    static final String COL_DP_PK = "iot_dp_product_key";
    static final String COL_DP_CODE = "iot_dp_code";
    static final String COL_DP_DATA_TYPE = "iot_dp_data_type";
    static final String COL_DP_REQUIRED = "iot_dp_required";
    static final String COL_DP_UNIT = "iot_dp_unit";
    static final String COL_DP_VALUE_MIN = "iot_dp_value_min";
    static final String COL_DP_VALUE_MAX = "iot_dp_value_max";
    static final String COL_DP_STEP = "iot_dp_step";
    static final String COL_DP_ALARM = "iot_dp_alarm_thresholds";

    static final String COL_E_PK = "iot_e_product_key";
    static final String COL_E_CODE = "iot_e_code";
    static final String COL_E_TYPE = "iot_e_event_type";
    static final String COL_E_OUTPUT_SCHEMA = "iot_e_output_data_schema";

    static final String COL_S_PK = "iot_s_product_key";
    static final String COL_S_CODE = "iot_s_code";
    static final String COL_S_CALL_TYPE = "iot_s_call_type";
    static final String COL_S_INPUT_SCHEMA = "iot_s_input_data_schema";
    static final String COL_S_OUTPUT_SCHEMA = "iot_s_output_data_schema";

    private static final TypeReference<Map<String, String>> NAME_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> RANGE_TYPE = new TypeReference<>() { };

    private static final long SYSTEM_USER_ID = 0L;
    private static final int MAX_PER_PRODUCT = 2000;

    private final DynamicDataService dynamicDataService;
    private final ObjectMapper objectMapper;

    public IotProductAccessorImpl(DynamicDataService dynamicDataService, ObjectMapper objectMapper) {
        this.dynamicDataService = dynamicDataService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ProductView> lookupByKey(long tenantId, String productKey) {
        if (tenantId <= 0 || productKey == null || productKey.isBlank()) {
            return Optional.empty();
        }
        return withTenant(tenantId, () -> {
            Optional<Map<String, Object>> row = findRow(MODEL_PRODUCT, COL_P_KEY, productKey);
            return row.map(r -> new ProductView(
                    IotDeviceAccessorImpl.asString(r.get(COL_P_KEY)),
                    parseLocalizedName(r.get(COL_P_NAME)),
                    IotDeviceAccessorImpl.asString(r.get(COL_P_NODE_TYPE)),
                    IotDeviceAccessorImpl.asString(r.get(COL_P_DATA_FORMAT)),
                    IotDeviceAccessorImpl.asString(r.get(COL_P_TRANSPORT_TYPE)),
                    IotDeviceAccessorImpl.asString(r.get(COL_P_PROVISION_TYPE)),
                    IotDeviceAccessorImpl.asLong(r.get("tenant_id"))));
        });
    }

    @Override
    public Optional<ProductSchema> getSchema(String productKey) {
        if (productKey == null || productKey.isBlank()) {
            return Optional.empty();
        }
        // Schema rows (data points / events / services) are tenant-scoped, so the
        // lookup MUST run under the caller's tenant — not tenant 0, which matches no
        // real tenant and so returned an empty schema for every product (it broke
        // invoke_service's TSL-service validation: every call failed
        // iot.error.service_not_found_in_schema, caught by the 2026-06-05 D3 golden).
        // Falls back to the system tenant only when there is no ambient context.
        return withTenant(resolveSchemaLookupTenant(), () -> {
            List<PropertyDef> props = listByPk(MODEL_DATA_POINT, COL_DP_PK, productKey).stream()
                    .map(this::toProperty)
                    .toList();
            List<EventDef> events = listByPk(MODEL_EVENT, COL_E_PK, productKey).stream()
                    .map(this::toEvent)
                    .toList();
            List<ServiceDef> services = listByPk(MODEL_SERVICE, COL_S_PK, productKey).stream()
                    .map(this::toService)
                    .toList();
            if (props.isEmpty() && events.isEmpty() && services.isEmpty()) {
                // No schema rows authored at all — treat as "no schema".
                return Optional.<ProductSchema>empty();
            }
            return Optional.of(new ProductSchema(props, events, services));
        });
    }

    private Optional<Map<String, Object>> findRow(String model, String column, String value) {
        DynamicQueryRequest req = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(2)
                .conditions(List.of(QueryCondition.builder()
                        .fieldName(column)
                        .operator(QueryCondition.Operator.EQ)
                        .value(value)
                        .build()))
                .build();
        try {
            PaginationResult<Map<String, Object>> page = dynamicDataService.list(model, req);
            List<Map<String, Object>> rows = page != null ? page.getRecords() : null;
            if (rows == null || rows.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(rows.get(0));
        } catch (RuntimeException e) {
            log.debug("[iot-product-accessor] list({}) failed: {}", model, e.getMessage());
            return Optional.empty();
        }
    }

    private List<Map<String, Object>> listByPk(String model, String column, String productKey) {
        DynamicQueryRequest req = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(MAX_PER_PRODUCT)
                .conditions(List.of(QueryCondition.builder()
                        .fieldName(column)
                        .operator(QueryCondition.Operator.EQ)
                        .value(productKey)
                        .build()))
                .build();
        try {
            PaginationResult<Map<String, Object>> page = dynamicDataService.list(model, req);
            List<Map<String, Object>> rows = page != null ? page.getRecords() : null;
            return rows == null ? List.of() : rows;
        } catch (RuntimeException e) {
            log.debug("[iot-product-accessor] schema list({}) failed: {}", model, e.getMessage());
            return List.of();
        }
    }

    private PropertyDef toProperty(Map<String, Object> r) {
        Map<String, Object> range = new LinkedHashMap<>();
        Object min = r.get(COL_DP_VALUE_MIN);
        Object max = r.get(COL_DP_VALUE_MAX);
        Object step = r.get(COL_DP_STEP);
        if (min != null) {
            range.put("min", min);
        }
        if (max != null) {
            range.put("max", max);
        }
        if (step != null) {
            range.put("step", step);
        }
        Object alarm = r.get(COL_DP_ALARM);
        if (alarm != null) {
            Map<String, Object> alarmRange = parseRange(alarm);
            if (!alarmRange.isEmpty()) {
                range.put("alarm", alarmRange);
            }
        }
        return new PropertyDef(
                IotDeviceAccessorImpl.asString(r.get(COL_DP_CODE)),
                IotDeviceAccessorImpl.asString(r.get(COL_DP_DATA_TYPE)),
                asBoolean(r.get(COL_DP_REQUIRED)),
                IotDeviceAccessorImpl.asString(r.get(COL_DP_UNIT)),
                range);
    }

    private EventDef toEvent(Map<String, Object> r) {
        return new EventDef(
                IotDeviceAccessorImpl.asString(r.get(COL_E_CODE)),
                IotDeviceAccessorImpl.asString(r.get(COL_E_TYPE)),
                false,
                null,
                parseRange(r.get(COL_E_OUTPUT_SCHEMA)));
    }

    private ServiceDef toService(Map<String, Object> r) {
        Map<String, Object> range = new LinkedHashMap<>();
        Map<String, Object> input = parseRange(r.get(COL_S_INPUT_SCHEMA));
        Map<String, Object> output = parseRange(r.get(COL_S_OUTPUT_SCHEMA));
        if (!input.isEmpty()) {
            range.put("input", input);
        }
        if (!output.isEmpty()) {
            range.put("output", output);
        }
        return new ServiceDef(
                IotDeviceAccessorImpl.asString(r.get(COL_S_CODE)),
                IotDeviceAccessorImpl.asString(r.get(COL_S_CALL_TYPE)),
                false,
                null,
                range);
    }

    private Map<String, String> parseLocalizedName(Object v) {
        if (v == null) {
            return Collections.emptyMap();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey().toString(), e.getValue().toString());
                }
            }
            return out;
        }
        String s = v.toString();
        if (s.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(s, NAME_TYPE);
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (Exception e) {
            log.debug("[iot-product-accessor] localized name parse failed: {}", e.getMessage());
            return Map.of("default", s);
        }
    }

    private Map<String, Object> parseRange(Object v) {
        if (v == null) {
            return new LinkedHashMap<>();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    out.put(e.getKey().toString(), e.getValue());
                }
            }
            return out;
        }
        String s = v.toString();
        if (s.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(s, RANGE_TYPE);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            log.debug("[iot-product-accessor] range JSON parse failed: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private boolean asBoolean(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = v.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "y".equalsIgnoreCase(s);
    }

    /**
     * The tenant under which to run a productKey schema lookup: the caller's
     * ambient tenant when present (schema rows are tenant-scoped), else the system
     * tenant as a last resort for no-context callers.
     */
    /** SystemTenantContextExecutor.SYSTEM_TENANT_ID — the platform system tenant. */
    private static final long SYSTEM_TENANT_ID = 1L;

    private long resolveSchemaLookupTenant() {
        if (MetaContext.exists()) {
            Long t = MetaContext.getCurrentTenantId();
            if (t != null && t > 0) {
                return t;
            }
        }
        return SYSTEM_TENANT_ID;
    }

    private <T> T withTenant(long tenantId, java.util.function.Supplier<T> work) {
        boolean had = MetaContext.exists();
        Long priorTenant = had ? MetaContext.getCurrentTenantId() : null;
        Long priorUser = had ? MetaContext.getCurrentUserId() : null;
        String priorUserPid = had ? MetaContext.getCurrentUserPid() : null;
        String priorUsername = had ? MetaContext.getCurrentUsername() : null;
        java.util.Set<Long> priorRoles = had ? MetaContext.getCurrentRoleIds() : java.util.Set.of();
        MetaContext.setContext(tenantId, SYSTEM_USER_ID, null, "system");
        try {
            return work.get();
        } finally {
            if (had) {
                MetaContext.setContext(priorTenant, priorUser, priorUserPid, priorUsername, priorRoles);
            } else {
                MetaContext.clear();
            }
        }
    }
}
