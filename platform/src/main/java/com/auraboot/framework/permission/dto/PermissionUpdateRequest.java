package com.auraboot.framework.permission.dto;

import lombok.Data;
import java.util.Map;

/**
 * Permission Update Request DTO (V4)
 * 
 * Used for updating existing permissions through Git-first workflow.
 * 
 * @author AuraBoot Platform
 * @version 4.0.0
 * @since 2025-01-07
 */
@Data
public class PermissionUpdateRequest {
    
    private String name;
    private String description;
    
    // Data Permission Extension
    private String dataScopeType;
    private Map<String, Object> dataScopeConfig;
    
    // Metadata
    private Map<String, Object> extension;
    private String[] tags;
    
    // Note: code, resourceType, resourceCode, action are immutable
}
