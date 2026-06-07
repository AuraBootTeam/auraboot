package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.time.Instant;

/**
 * DTO for a decision definition (response and internal transfer).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class DrtDefinitionDTO {
    private Long id;
    private String pid;
    private Long tenantId;
    private String decisionCode;
    private String decisionName;
    private String description;
    private String scopeType;
    private String scopeRef;
    private String ownerModule;
    private Boolean enabled;
    private String createdBy;
    private Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;
}
