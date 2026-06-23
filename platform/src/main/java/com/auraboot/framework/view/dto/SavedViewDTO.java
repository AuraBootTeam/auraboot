package com.auraboot.framework.view.dto;

import com.auraboot.framework.view.entity.ViewConfig;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
    private String pid;
    @JsonIgnore
    private Long tenantId;

    private String name;
    private String description;

    private String modelCode;
    private String pageKey;

    private String scope;
    private String viewType;
    private String ownerId;
    private String teamId;

    private ViewConfig viewConfig;

    private Boolean allowFullModel;
    private Boolean isDefault;
    private Boolean isImplicit;
    private Integer sortOrder;

    /**
     * Effective permission for the current user on this view: view, save, or manage.
     */
    private String effectivePermission;

    /**
     * Server-authoritative actions the frontend may render for the current user.
     */
    private List<String> actions;

    /**
     * Whether this DTO already includes persisted local override changes.
     * Currently false until the local override table is introduced.
     */
    private Boolean dirty;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

    /**
     * Additional display fields (populated in service layer)
     */
    private String ownerName;
    private String teamName;
}
