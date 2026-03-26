package com.auraboot.framework.permission.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * SubjectPermission Data Transfer Object (V4)
 * 
 * Used for API responses and data transfer between layers.
 * 
 * @author AuraBoot Platform
 * @version 4.0.0
 * @since 2025-01-07
 */
@Data
public class SubjectPermissionDTO {
    
    private Long id;
    private String pid;
    
    // Tenant Isolation
    private Long tenantId;
     
    
    // Subject (Menu/Page/Button abstraction)
    private String subjectType;
    private Long subjectId;
    private String subjectCode;
    
    // Permission Reference
    private Long permissionId;
    private String permissionCode;  // Denormalized for convenience
    private String permissionName;  // Denormalized for convenience
    
    // Logic Control
    private Integer logicGroup;
    private String groupLogicType;
    private Boolean isNegated;
    private Integer logicOrder;
    
    // Requirement Type
    private String requirementType;
    
    // Status
    private String status;
    
    // Audit
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
}
