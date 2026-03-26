package com.auraboot.framework.meta.template.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Information about a generated permission
 * 
 * @author AuraBoot
 */
@Data
@Builder
public class GeneratedPermission {
    
    /**
     * Permission ID (database primary key)
     */
    private String id;
    
    /**
     * Permission PID (public identifier)
     */
    private String pid;
    
    /**
     * Permission code
     */
    private String permissionCode;
    
    /**
     * Permission name
     */
    private String permissionName;
    
    /**
     * Resource type
     */
    private String resourceType;
    
    /**
     * Resource ID
     */
    private String resourceId;
}
