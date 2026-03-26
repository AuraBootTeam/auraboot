package com.auraboot.framework.permission.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.time.LocalDate;

/**
 * Permission Reference DTO
 * 
 * Represents a role that references a permission.
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@Data
public class PermissionReferenceDTO {
    
    /**
     * Role Permission Binding ID
     */
    private Long id;
    
    /**
     * Role ID
     */
    private Long roleId;
    
    /**
     * Role Name
     */
    private String roleName;
    
    /**
     * Role Code
     */
    private String roleCode;
    
    /**
     * Grant Type (GRANT or DENY)
     */
    private String grantType;
    
    /**
     * Priority
     */
    private Integer priority;
    
    /**
     * Effective Date
     */
    private LocalDate effectiveDate;
    
    /**
     * Expiry Date
     */
    private LocalDate expiryDate;
    
    /**
     * Status (ACTIVE or INACTIVE)
     */
    private String status;
    
    /**
     * Created At
     */
    private LocalDateTime createdAt;
    
    /**
     * Created By
     */
    private Long createdBy;
}
