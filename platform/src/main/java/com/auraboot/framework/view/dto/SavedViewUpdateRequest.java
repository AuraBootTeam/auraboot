package com.auraboot.framework.view.dto;

import com.auraboot.framework.view.entity.ViewConfig;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for updating a SavedView
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
public class SavedViewUpdateRequest {

    @Size(max = 100, message = "View name must be less than 100 characters")
    private String name;

    @Size(max = 500, message = "Description must be less than 500 characters")
    private String description;

    /**
     * Scope: PERSONAL, TEAM, GLOBAL
     */
    private String scope;

    /**
     * View type (immutable after creation, ignored on update)
     */
    private String viewType;

    /**
     * Team ID (required when scope is TEAM)
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
