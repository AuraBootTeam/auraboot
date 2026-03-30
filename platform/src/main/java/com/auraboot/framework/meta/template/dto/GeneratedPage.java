package com.auraboot.framework.meta.template.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Information about a generated page
 * 
 * @author AuraBoot
 */
@Data
@Builder
public class GeneratedPage {
    
    /**
     * Page ID (database primary key)
     */
    private String id;
    
    /**
     * Page PID (public identifier)
     */
    private String pid;
    
    /**
     * Page name
     */
    private String pageName;
    
    /**
     * Page kind (list/form/detail)
     */
    private String kind;
    
    /**
     * Route path
     */
    private String route;
    
    /**
     * Creation timestamp
     */
    private String createdAt;
}
