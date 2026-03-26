package com.auraboot.framework.permission.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Permission Bind Request
 * 
 * Used for binding/unbinding permissions to/from roles.
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@Data
public class PermissionBindRequest {
    
    /**
     * Permission ID to bind/unbind
     */
    @NotNull(message = "Permission ID cannot be null")
    private Long permissionId;
    
    /**
     * Priority (optional, default: 0)
     */
    private Integer priority;
    
    /**
     * Grant type (optional, default: GRANT)
     * Values: GRANT, DENY
     */
    private String grantType;
}
