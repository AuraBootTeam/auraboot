package com.auraboot.framework.permission.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * SubjectPermission Create Request DTO (V4)
 * 
 * Used for adding permission declarations to subjects.
 * 
 * @author AuraBoot Platform
 * @version 4.0.0
 * @since 2025-01-07
 */
@Data
public class SubjectPermissionCreateRequest {
    
    @NotBlank(message = "Subject type is required")
    private String subjectType;
    
    @NotNull(message = "Subject ID is required")
    private Long subjectId;
    
    private String subjectCode;
    
    @NotNull(message = "Permission ID is required")
    private Long permissionId;
    
    // Logic Control
    private Integer logicGroup = 0;
    private String groupLogicType = "or";
    private Boolean isNegated = false;
    private Integer logicOrder = 0;
    
    // Requirement Type
    private String requirementType = "view";
}
