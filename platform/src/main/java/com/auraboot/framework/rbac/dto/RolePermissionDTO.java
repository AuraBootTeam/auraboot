package com.auraboot.framework.rbac.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Map;

/**
 * RolePermission Data Transfer Object (V4)
 * 
 * Used for API responses and data transfer between layers.
 * 
 * @author AuraBoot Platform
 * @version 4.0.0
 * @since 2025-01-07
 */
@Data
public class RolePermissionDTO {
    
    private Long id;
    private String pid;
    
    // Tenant Isolation
    private Long tenantId;
     
    
    // Binding Relationship
    private Long roleId;
    private String roleName;  // Denormalized for convenience
    private Long permissionId;
    private String permissionCode;  // Denormalized for convenience
    private String permissionName;  // Denormalized for convenience
    
    // Grant Control
    private String grantType;
    private Integer priority;
    
    // Temporal Control
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    
    // Conditions
    private Map<String, Object> conditions;
    
    // Status
    private String status;
    
    // Audit
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
}
