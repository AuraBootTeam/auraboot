package com.auraboot.framework.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for creating a Dashboard
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class DashboardCreateRequest {

    @Size(max = 100, message = "Dashboard code must be less than 100 characters")
    private String code;

    @NotBlank(message = "Dashboard title is required")
    @Size(max = 200, message = "Dashboard title must be less than 200 characters")
    private String title;

    @Size(max = 500, message = "Description must be less than 500 characters")
    private String description;

    /**
     * Scope: PERSONAL (default), TEAM, GLOBAL
     */
    private String scope;

    /**
     * Team ID (required when scope is TEAM)
     */
    private String teamId;

    /**
     * Layout configuration
     * Default: { columns: 12, rowHeight: 100, gap: 16 }
     */
    private JsonNode layoutConfig;

    /**
     * Initial widgets (optional)
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
