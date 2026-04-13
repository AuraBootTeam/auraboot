package com.auraboot.framework.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Dashboard Data Transfer Object
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardDTO {

    private Long id;
    private String pid;
    private Long tenantId;

    private String code;
    private String title;
    private String description;

    private String scope;
    private String ownerId;
    private String teamId;

    private JsonNode layoutConfig;
    private JsonNode widgets;

    private String status;
    private Boolean isDefault;
    private Integer sortOrder;

    private JsonNode extension;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

    /**
     * Additional display fields (populated in service layer)
     */
    private String ownerName;
    private String teamName;

    /**
     * Whether this dashboard is mounted to sidebar menu (computed from extension JSONB)
     */
    private Boolean menuMounted;

    /**
     * Menu code if mounted (computed from extension JSONB)
     */
    private String menuCode;
}
