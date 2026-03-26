package com.auraboot.framework.meta.template.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Access links for generated pages
 * 
 * @author AuraBoot
 */
@Data
@Builder
public class AccessLinks {
    
    /**
     * List page link
     */
    private String listPage;
    
    /**
     * Form page link
     */
    private String formPage;
    
    /**
     * Detail page link
     */
    private String detailPage;
}
