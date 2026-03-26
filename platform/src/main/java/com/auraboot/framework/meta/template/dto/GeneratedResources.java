package com.auraboot.framework.meta.template.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Container for all generated resources
 * 
 * @author AuraBoot
 */
@Data
@Builder
public class GeneratedResources {
    
    /**
     * Generated pages
     */
    private List<GeneratedPage> pages;
    
    /**
     * Generated menus
     */
    private List<GeneratedMenu> menus;
    
    /**
     * Generated permissions
     */
    private List<GeneratedPermission> permissions;
}
