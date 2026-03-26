package com.auraboot.framework.view.dto;

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
    private String description;

    @NotBlank(message = "Model code is required")
    private String modelCode;

    /**
     * Page key (optional - null for model-level view)
     */
    private String pageKey;

    /**
     * Scope: PERSONAL (default), TEAM, GLOBAL.
     */
    private String scope;

    /**
     * View type: TABLE (default), KANBAN, CALENDAR, GALLERY, GANTT, TREE
     */
    private String viewType;

    /**
     * Team ID (required when scope = TEAM)
     */
    private String teamId;

    /**
     * View configuration
     */
    private ViewConfig viewConfig;

    /**
     * Whether to allow full model field access
     */
    private Boolean allowFullModel;

    /**
     * Whether this is the default view
     */
    private Boolean isDefault;

    /**
     * Sort order for display
     */
    private Integer sortOrder;
}
