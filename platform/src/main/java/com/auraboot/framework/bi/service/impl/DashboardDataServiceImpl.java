package com.auraboot.framework.bi.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.auraboot.framework.bi.dto.DashboardDataResponse;
import com.auraboot.framework.bi.service.DashboardDataService;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.common.util.JsonUtil;
import com.auraboot.framework.datasource.dao.mapper.DynamicQueryMapper;
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
    private final DynamicQueryMapper dynamicQueryMapper;

    /** Simple in-memory cache: dashboardId -> (data, timestamp) */
    private final Map<String, CachedData> cache = new ConcurrentHashMap<>();

    private static final int DEFAULT_CACHE_TTL_SECONDS = 30;

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

    @SuppressWarnings("unchecked")
    private Object fetchWidgetData(Map<?, ?> widget, Long tenantId) {
        Object dataSource = widget.get("dataSource");
        if (dataSource == null) {
            // Static widget (text, clock, etc.) - return config as-is
            return widget.get("config");
        }

        if (dataSource instanceof Map<?, ?> ds) {
            Object typeVal = ds.get("type");
            String type = typeVal != null ? String.valueOf(typeVal) : "sql";
            switch (type) {
                case "sql" -> {
                    String sql = String.valueOf(ds.get("query"));
                    if (sql != null && !sql.isBlank()) {
                        try {
                            return dynamicQueryMapper.queryData(sql);
                        } catch (Exception e) {
                            log.warn("Widget SQL query failed: {}", e.getMessage());
                            return Map.of("error", e.getMessage());
                        }
                    }
                }
                case "static" -> {
                    return ds.get("data");
                }
                default -> log.warn("Unknown widget dataSource type: {}", type);
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
