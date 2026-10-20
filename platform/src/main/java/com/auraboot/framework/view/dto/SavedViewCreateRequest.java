package com.auraboot.framework.view.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.auraboot.framework.view.entity.ViewConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for creating a SavedView
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
public class SavedViewCreateRequest {

    @NotBlank(message = "View name is required")
    @Size(max = 100, message = "View name must be less than 100 characters")
    private String name;

    @Size(max = 500, message = "Description must be less than 500 characters")
    @Schema(nullable = true)
    private String description;

    @NotBlank(message = "Model code is required")
    private String modelCode;

    /**
     * Page key (optional - null for model-level view)
     */
    @Schema(nullable = true)
    private String pageKey;

    /**
     * Scope: PERSONAL (default), TEAM, ROLE, GLOBAL.
     */
    @Schema(nullable = true)
    private String scope;

    /**
     * View type: TABLE (default), KANBAN, CALENDAR, GALLERY, GANTT, TREE
     */
    @Schema(nullable = true)
    private String viewType;

    /**
     * Team ID (required when scope = TEAM)
     */
    @Schema(nullable = true)
    private String teamId;

    /**
     * Role PID (required when scope = ROLE)
     */
    @Schema(nullable = true)
    private String roleId;

    /**
     * View configuration
     */
    @Schema(nullable = true)
    private ViewConfig viewConfig;

    /**
     * Whether to allow full model field access
     */
    @Schema(nullable = true)
    private Boolean allowFullModel;

    /**
     * Whether this is the default view
     */
    @Schema(nullable = true)
    private Boolean isDefault;

    /**
     * Sort order for display
     */
    @Schema(nullable = true)
    private Integer sortOrder;
}
