package com.auraboot.framework.meta.template.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Result of CRUD template generation
 * 
 * @author AuraBoot
 */
@Data
@Builder
public class TemplateGenerationResult {
    
    /**
     * Model code
     */
    private String modelCode;
    
    /**
     * Generated resources
     */
    private GeneratedResources generatedResources;
    
    /**
     * Access links
     */
    private AccessLinks accessLinks;
    
    /**
     * Generation timestamp
     */
    private Instant generatedAt;
}
