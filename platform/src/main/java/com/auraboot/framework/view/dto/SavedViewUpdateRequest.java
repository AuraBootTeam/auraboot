package com.auraboot.framework.view.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
    @Schema(nullable = true)
    private String name;

    @Size(max = 500, message = "Description must be less than 500 characters")
    @Schema(nullable = true)
    private String description;

    /**
     * Scope: PERSONAL, TEAM, ROLE, GLOBAL
     */
    @Schema(nullable = true)
    private String scope;

    /**
     * View type (immutable after creation, ignored on update)
     */
    @Schema(nullable = true)
    private String viewType;

    /**
     * Team ID (required when scope is TEAM)
     */
    @Schema(nullable = true)
    private String teamId;

    /**
     * Role PID (required when scope is ROLE)
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
