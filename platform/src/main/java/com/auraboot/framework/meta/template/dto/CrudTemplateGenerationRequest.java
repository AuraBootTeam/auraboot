package com.auraboot.framework.meta.template.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for CRUD template generation
 * 
 * @author AuraBoot
 */
@Data
public class CrudTemplateGenerationRequest {
    
    /**
     * Model code
     */
    @NotBlank(message = "Model code cannot be empty")
    private String modelCode;
    
    /**
     * Generation configuration
     */
    @Valid
    private CrudTemplateConfig config;
}
