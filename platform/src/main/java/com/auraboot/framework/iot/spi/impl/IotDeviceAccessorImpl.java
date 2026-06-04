package com.auraboot.framework.iot.spi.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.iot.BackgroundDeviceAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link BackgroundDeviceAccessor} implementation backed by the
 * platform's dynamic-data layer. Reads device rows from the
 * {@code mt_iot_device} table (auto-created on plugin import) and projects
 * them into the stable {@link DeviceView} record.
 *
 * <p>Tenant isolation: {@link #lookupByCode(long, String)} pins the supplied
 * tenant id via {@link MetaContext} (mirroring
 * {@link com.auraboot.framework.plugin.pf4j.BackgroundDataAccessorImpl}) so
 * the dynamic-data filter chain enforces row-level isolation. The
 * tenant-agnostic {@link #lookupByIotId(String)} path uses a system tenant
 * context with {@code iot_d_iot_id} as the unique key.
 *
 * @since 2.6.0
 */
@Slf4j
@Service
public class IotDeviceAccessorImpl implements BackgroundDeviceAccessor {

    static final String MODEL_CODE = "iot_device";
    static final String COL_DEVICE_CODE = "iot_d_device_code";
    static final String COL_IOT_ID = "iot_d_iot_id";
    static final String COL_PRODUCT_KEY = "iot_d_product_key";
    static final String COL_STATUS = "iot_d_status";
    static final String COL_ACL_PATTERN = "iot_d_acl_pattern";
    static final String COL_TAGS = "iot_d_tags";
    static final String COL_LAST_SEEN_AT = "iot_d_last_seen_at";

    private static final TypeReference<Map<String, String>> TAGS_TYPE = new TypeReference<>() { };
    private static final long SYSTEM_USER_ID = 0L;

    private final DynamicDataService dynamicDataService;
    private final ObjectMapper objectMapper;

    public IotDeviceAccessorImpl(DynamicDataService dynamicDataService, ObjectMapper objectMapper) {
        this.dynamicDataService = dynamicDataService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<DeviceView> lookupByCode(long tenantId, String deviceCode) {
        if (tenantId <= 0 || deviceCode == null || deviceCode.isBlank()) {
            return Optional.empty();
        }
        return withTenant(tenantId, () -> findOne(COL_DEVICE_CODE, deviceCode, tenantId));
    }

    @Override
    public Optional<DeviceView> lookupByIotId(String iotId) {
        if (iotId == null || iotId.isBlank()) {
            return Optional.empty();
        }
        // iotId is globally unique; we still need a tenant to drive the
        // dynamic-data query layer. Use a system context and let the row's
        // own tenant id (populated by MetaContext on insert) flow through.
        return withTenant(0L, () -> findOne(COL_IOT_ID, iotId, null));
    }

    private Optional<DeviceView> findOne(String column, String value, Long expectedTenant) {
        DynamicQueryRequest req = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(2)
                .conditions(List.of(QueryCondition.builder()
                        .fieldName(column)
                        .operator(QueryCondition.Operator.EQ)
                        .value(value)
                        .build()))
                .build();
        PaginationResult<Map<String, Object>> page;
        try {
            page = dynamicDataService.list(MODEL_CODE, req);
        } catch (RuntimeException e) {
            log.debug("[iot-device-accessor] list failed col={} val={}: {}", column, value, e.getMessage());
            return Optional.empty();
        }
        List<Map<String, Object>> rows = page != null ? page.getRecords() : null;
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        DeviceView view = toView(row);
        if (expectedTenant != null && view.tenantId() != expectedTenant) {
            // Cross-tenant defensive: the dynamic-data filter chain should
            // already prevent this, but if it doesn't we refuse to leak.
            return Optional.empty();
        }
        return Optional.of(view);
    }

    private DeviceView toView(Map<String, Object> row) {
        return new DeviceView(
                asString(row.get("pid")),
                asString(row.get(COL_IOT_ID)),
                asString(row.get(COL_DEVICE_CODE)),
                asString(row.get(COL_PRODUCT_KEY)),
                asLong(row.get("tenant_id")),
                asString(row.get(COL_STATUS)),
                asString(row.get(COL_ACL_PATTERN)),
                parseTags(row.get(COL_TAGS)),
                asInstant(row.get(COL_LAST_SEEN_AT)));
    }

    static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    static long asLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    static Instant asInstant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (v instanceof java.time.LocalDateTime ldt) {
            return ldt.toInstant(ZoneOffset.UTC);
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant();
        }
        try {
            return Instant.parse(v.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<String, String> parseTags(Object v) {
        if (v == null) {
            return Collections.emptyMap();
        }
        if (v instanceof Map<?, ?> m) {
            // Already deserialised by the dynamic-data layer.
            return m.entrySet().stream()
                    .filter(e -> e.getKey() != null && e.getValue() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().toString(),
                            e -> e.getValue().toString(),
                            (a, b) -> a,
                            java.util.LinkedHashMap::new));
        }
        String s = v.toString();
        if (s.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(s, TAGS_TYPE);
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (Exception e) {
            log.debug("[iot-device-accessor] tags JSON parse failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
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
