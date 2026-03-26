package com.auraboot.framework.meta.template.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Information about a generated menu
 * 
 * @author AuraBoot
 */
@Data
@Builder
public class GeneratedMenu {
    
    /**
     * Menu ID (database primary key)
     */
    private String id;
    
    /**
     * Menu PID (public identifier)
     */
    private String pid;
    
    /**
     * Menu name
     */
    private String menuName;
    
    /**
     * Menu path
     */
    private String menuPath;
    
    /**
     * Icon
     */
    private String icon;
    
    /**
     * Display order
     */
    private Integer displayOrder;
}
