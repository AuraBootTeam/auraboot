package com.auraboot.framework.meta.template.service;

import com.auraboot.framework.meta.template.dto.CrudTemplateConfig;
import com.auraboot.framework.meta.template.dto.TemplateGenerationResult;

/**
 * Template Generator Service interface
 * 
 * @author AuraBoot
 */
public interface TemplateGeneratorService {
    
    /**
     * Generate CRUD pages
     * 
     * @param modelCode Model code
     * @param config Generation configuration
     * @return Generation result
     */
    TemplateGenerationResult generateCrudPages(String modelCode, CrudTemplateConfig config);
    
    /**
     * Validate generation configuration
     * 
     * @param modelCode Model code
     * @param config Generation configuration
     */
    void validateConfiguration(String modelCode, CrudTemplateConfig config);
}
