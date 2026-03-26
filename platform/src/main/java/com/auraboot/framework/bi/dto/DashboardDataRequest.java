package com.auraboot.framework.bi.dto;

import lombok.Data;

/**
 * Request DTO for batch-fetching all widget data for a dashboard.
 */
@Data
public class DashboardDataRequest {

    /** Dashboard schema ID */
    private String dashboardId;

    /** Whether to force refresh (bypass cache) */
    private boolean forceRefresh = false;
}
