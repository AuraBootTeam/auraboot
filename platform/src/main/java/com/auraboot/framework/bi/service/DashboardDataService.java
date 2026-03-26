package com.auraboot.framework.bi.service;

import com.auraboot.framework.bi.dto.DashboardDataResponse;

/**
 * Service for batch-fetching all widget data for a dashboard.
 * Used by the Data Screen auto-refresh feature.
 */
public interface DashboardDataService {

    /**
     * Fetch all widget data for a dashboard in one call.
     *
     * @param dashboardId the dashboard schema ID
     * @param forceRefresh bypass cache if true
     * @param tenantId current tenant
     * @return all widget data bundled together
     */
    DashboardDataResponse fetchDashboardData(String dashboardId, boolean forceRefresh, Long tenantId);
}
