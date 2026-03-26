package com.auraboot.framework.bi.dto;

import lombok.Data;

import java.util.Map;

/**
 * Response DTO containing all widget data for a dashboard in a single payload.
 * Key = widget ID, Value = widget data (could be list, map, number, etc.)
 */
@Data
public class DashboardDataResponse {

    /** Map of widgetId -> widget data */
    private Map<String, Object> widgets;

    /** Timestamp when this data was fetched */
    private long fetchedAt;

    /** Cache TTL in seconds (how long until next refresh is needed) */
    private int cacheTtl;

    /** Dashboard title for display */
    private String dashboardTitle;
}
