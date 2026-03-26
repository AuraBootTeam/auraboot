package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * Field search request DTO
 * Used for advanced field search with multiple filters
 */
@Data
public class FieldSearchRequest {
    
    /**
     * Search keyword (searches in code and description)
     */
    private String keyword;
    
    /**
     * Filter by base type
     */
    private String baseType;
    
    /**
     * Filter by semantic type
     */
    private String semanticType;
    
    /**
     * Minimum usage count filter
     */
    private Integer minUsageCount;
    
    /**
     * Maximum usage count filter
     */
    private Integer maxUsageCount;
    
    /**
     * Only return system fields
     */
    private Boolean systemFieldsOnly;
    
    /**
     * Only return unused fields
     */
    private Boolean unusedOnly;
    
    /**
     * Page number (1-based)
     */
    private Integer page;
    
    /**
     * Page size
     */
    private Integer size;
}
