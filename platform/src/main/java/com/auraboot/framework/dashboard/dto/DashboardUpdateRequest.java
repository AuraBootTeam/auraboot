package com.auraboot.framework.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for updating a Dashboard
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class DashboardUpdateRequest {

    @Size(max = 200, message = "Dashboard title must be less than 200 characters")
    private String title;

    @Size(max = 500, message = "Description must be less than 500 characters")
    private String description;

    /**
     * Scope: PERSONAL, TEAM, GLOBAL
     */
    private String scope;

    /**
     * Team ID (required when scope is TEAM)
     */
    private String teamId;

    /**
     * Layout configuration
     */
    private JsonNode layoutConfig;

    /**
     * Widgets configuration
     */
    private JsonNode widgets;

    /**
     * Whether this is the default dashboard
     */
    private Boolean isDefault;

    /**
     * Sort order for display
     */
    private Integer sortOrder;

    /**
     * Extension data
     */
    private JsonNode extension;
}
