package com.auraboot.framework.permission.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Permission Create Request DTO (V4)
 * 
 * Used for creating new permissions through Git-first workflow.
 * 
 * @author AuraBoot Platform
 * @version 4.0.0
 * @since 2025-01-07
 */
@Data
public class PermissionCreateRequest {
    
    @NotBlank(message = "Permission code is required")
    private String code;
    
    @NotBlank(message = "Permission name is required")
    private String name;
    
    private String description;
    
    @NotBlank(message = "Resource type is required")
    private String resourceType;
    
    @NotBlank(message = "Resource code is required")
    private String resourceCode;
    
    @NotBlank(message = "Action is required")
    private String action;
    
    // Source Tracking
    private String source;
    private String sourceRef;
    
    // Hierarchy Support
    private Long parentId;
    
    // Data Permission Extension
    private String dataScopeType;
    private Map<String, Object> dataScopeConfig;
    
    // Metadata
    private Map<String, Object> extension;
    private String[] tags;

    /**
     * 插件PID（用于标识资源来源的插件）
     */
    private String pluginPid;
}
