package com.auraboot.framework.view.dto;

import com.auraboot.framework.view.entity.ViewConfig;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * SavedView Data Transfer Object
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavedViewDTO {

    @JsonIgnore
    private Long id;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String pid;
    @JsonIgnore
    private Long tenantId;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;
    @Schema(nullable = true)
    private String description;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String modelCode;
    @Schema(nullable = true)
    private String pageKey;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String scope;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String viewType;
    @Schema(nullable = true)
    private String ownerId;
    @Schema(nullable = true)
    private String teamId;
    @Schema(nullable = true)
    private String roleId;

    @Schema(nullable = true)
    private ViewConfig viewConfig;

    @Schema(nullable = true)
    private Boolean allowFullModel;
    @Schema(nullable = true)
    private Boolean isDefault;
    @Schema(nullable = true)
    private Boolean isImplicit;
    @Schema(nullable = true)
    private Integer sortOrder;

    /**
     * Effective permission for the current user on this view: view, save, or manage.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String effectivePermission;

    /**
     * Server-authoritative actions the frontend may render for the current user.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> actions;

    /**
     * Whether this DTO already includes persisted local override changes.
     * Currently false until the local override table is introduced.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean dirty;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant createdAt;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant updatedAt;
    @Schema(nullable = true)
    private String createdBy;
    @Schema(nullable = true)
    private String updatedBy;

    /**
     * Additional display fields (populated in service layer)
     */
    @Schema(nullable = true)
    private String ownerName;
    @Schema(nullable = true)
    private String teamName;
    @Schema(nullable = true)
    private String roleName;
}
