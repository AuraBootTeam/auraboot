package com.auraboot.framework.dashboard.dto;

import lombok.Data;

/**
 * Request DTO for querying Dashboards
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class DashboardQueryRequest {

    /**
     * Filter by title (fuzzy match)
     */
    private String title;

    /**
     * Filter by scope: PERSONAL, TEAM, GLOBAL
     */
    private String scope;

    /**
     * Filter by status: DRAFT, PUBLISHED
     */
    private String status;

    /**
     * Include only accessible dashboards for current user
     * Default: true
     */
    private Boolean accessibleOnly = true;

    /**
     * Page number (1-based)
     */
    private Integer pageNum = 1;

    /**
     * Page size
     */
    private Integer pageSize = 20;

    /**
     * Sort field
     */
    private String sortField = "updatedAt";

    /**
     * Sort direction: ASC, DESC
     */
    private String sortOrder = "desc";
}
