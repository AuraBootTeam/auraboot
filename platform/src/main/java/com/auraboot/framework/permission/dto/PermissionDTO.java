package com.auraboot.framework.permission.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Permission Data Transfer Object (V4)
 * 
 * Used for API responses and data transfer between layers.
 * 
 * @author AuraBoot Platform
 * @version 4.0.0
 * @since 2025-01-07
 */
@Data
public class PermissionDTO {
    
    private Long id;
    private String pid;
    
    // Tenant Isolation
    private Long tenantId;
     
    
    // Permission Identity
    private String code;
    private String name;
    private String description;
    
    // Resource Classification
    private String resourceType;
    private String resourceCode;
    private String action;
    
    // Source Tracking
    private String source;
    private String sourceRef;
    
    // Hierarchy Support
    private Long parentId;
    private String path;
    private Integer level;
    
    // Data Permission Extension
    private String dataScopeType;
    private Map<String, Object> dataScopeConfig;
    
    // Metadata
    private Map<String, Object> extension;
    private String[] tags;

    // Policy Schema (configurable parameters for this permission)
    private Object policySchema;
    
    // Lifecycle Status
    private String status;
    private LocalDateTime deprecatedAt;
    private LocalDateTime archivedAt;
    
    // Audit
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
}
