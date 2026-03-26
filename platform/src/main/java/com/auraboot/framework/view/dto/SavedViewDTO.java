package com.auraboot.framework.view.dto;

import com.auraboot.framework.view.entity.ViewConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

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

    private Long id;
    private String pid;
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
    private Integer sortOrder;

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
