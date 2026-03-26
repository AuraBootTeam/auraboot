package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Affected model DTO
 * Represents a model affected by field modification
 */
@Data
@Builder
public class AffectedModel {
    
    /**
     * Model PID
     */
    private String modelPid;
    
    /**
     * Model code
     */
    private String modelCode;
    
    /**
     * Model display name
     */
    private String modelDisplayName;
    
    /**
     * Current binding configuration
     */
    private BindingConfiguration currentBinding;
    
    /**
     * List of potential issues
     */
    private List<String> potentialIssues;
    
    /**
     * Impact level: HIGH, MEDIUM, LOW
     */
    private String impactLevel;
}
