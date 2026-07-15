package com.auraboot.framework.bi.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.auraboot.framework.bi.dto.DashboardDataResponse;
import com.auraboot.framework.bi.service.DashboardDataService;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Batch-fetches all widget data for a dashboard.
 * Provides simple in-memory caching with TTL for auto-refresh scenarios.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardDataServiceImpl implements DashboardDataService {

    private final PageSchemaMapper formSchemaMapper;

    /** Simple in-memory cache: dashboardId -> (data, timestamp) */
    private final Map<String, CachedData> cache = new ConcurrentHashMap<>();

    private static final int DEFAULT_CACHE_TTL_SECONDS = 30;

    /** Widget error surfaced when a rejected data source type is encountered. */
    private static final String FREE_SQL_REJECTED_MESSAGE =
            "Free-form SQL data sources are not supported for dashboard widgets";

    @Override
    public DashboardDataResponse fetchDashboardData(String dashboardId, boolean forceRefresh, Long tenantId) {
        // Check cache first
        if (!forceRefresh) {
            CachedData cached = cache.get(dashboardId);
            if (cached != null && !cached.isExpired()) {
                log.debug("Returning cached dashboard data for {}", dashboardId);
                return cached.response;
            }
        }

        // Fetch dashboard schema
        PageSchema schema = formSchemaMapper.selectById(dashboardId);
        if (schema == null) {
            throw new IllegalArgumentException("Dashboard not found: " + dashboardId);
        }

        Map<String, Object> dashboardConfig;
        Object rawData = schema.getBlocks();
        try {
            if (rawData instanceof String dataJson) {
                dashboardConfig = JsonUtil.parse(dataJson, new TypeReference<>() {});
            } else if (rawData instanceof Map<?, ?> mapData) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) mapData;
                dashboardConfig = cast;
            } else {
                dashboardConfig = Collections.emptyMap();
            }
        } catch (Exception e) {
            log.error("Failed to parse dashboard config for {}", dashboardId, e);
            dashboardConfig = Collections.emptyMap();
        }

        // Extract widgets and fetch their data
        Map<String, Object> widgetData = new LinkedHashMap<>();
        Object widgetsObj = dashboardConfig.get("widgets");
        if (widgetsObj instanceof List<?> widgets) {
            for (Object w : widgets) {
                if (w instanceof Map<?, ?> widget) {
                    String widgetId = String.valueOf(widget.get("id"));
                    Object data = fetchWidgetData(widget, tenantId);
                    widgetData.put(widgetId, data);
                }
            }
        }

        // Extract data screen config
        int cacheTtl = DEFAULT_CACHE_TTL_SECONDS;
        Object dataScreenConfig = dashboardConfig.get("dataScreen");
        if (dataScreenConfig instanceof Map<?, ?> dsConfig) {
            Object interval = dsConfig.get("refreshInterval");
            if (interval instanceof Number) {
                cacheTtl = ((Number) interval).intValue();
            }
        }

        // Build response
        DashboardDataResponse response = new DashboardDataResponse();
        response.setWidgets(widgetData);
        response.setFetchedAt(System.currentTimeMillis());
        response.setCacheTtl(cacheTtl);
        Object titleVal = dashboardConfig.get("title");
        response.setDashboardTitle(titleVal != null ? String.valueOf(titleVal) : "Dashboard");

        // Update cache
        cache.put(dashboardId, new CachedData(response, cacheTtl));

        return response;
    }

    private Object fetchWidgetData(Map<?, ?> widget, Long tenantId) {
        Object dataSource = widget.get("dataSource");
        if (dataSource == null) {
            // Static widget (text, clock, etc.) - return config as-is
            return widget.get("config");
        }

        if (dataSource instanceof Map<?, ?> ds) {
            Object typeVal = ds.get("type");
            // Require an explicit, safe data source type. A missing type must NOT
            // fall through to raw SQL execution as it did previously — that default
            // is what made no-type widgets a cross-tenant read path.
            String type = typeVal != null ? String.valueOf(typeVal) : "";
            switch (type) {
                case "static" -> {
                    return ds.get("data");
                }
                case "sql" -> {
                    // SECURITY (finding DR-20260702-SD2-DASHBOARD-003): free-form SQL
                    // widgets are rejected and never executed. Such a query would run
                    // through DynamicQueryMapper/SqlRunner, which bypasses the tenant
                    // line interceptor, so a SELECT against a shared table would read
                    // data across every tenant. Dashboard widgets must use tenant-scoped
                    // data sources (aggregate / namedQuery / static) — the only types the
                    // Dashboard Designer produces.
                    log.warn("Rejected free-form SQL dashboard widget; use a tenant-scoped data source instead");
                    return Map.of("error", FREE_SQL_REJECTED_MESSAGE);
                }
                default -> log.warn("Unsupported widget dataSource type: {}", type);
            }
        }

        return null;
    }

    private record CachedData(DashboardDataResponse response, int ttlSeconds) {
        boolean isExpired() {
            return System.currentTimeMillis() - response.getFetchedAt() > ttlSeconds * 1000L;
        }
    }
}
