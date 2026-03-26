package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Modification impact analysis result DTO
 * Used for analyzing the impact of field modifications
 */
@Data
@Builder
public class ModificationImpact {
    
    /**
     * Field PID being modified
     */
    private String fieldPid;
    
    /**
     * Modification type: BREAKING, WARNING, SAFE
     */
    private ModificationType modificationType;
    
    /**
     * List of affected models
     */
    private List<AffectedModel> affectedModels;
    
    /**
     * Total number of affected models
     */
    private Integer totalAffectedModels;
    
    /**
     * Impact description
     */
    private String impactDescription;
    
    /**
     * List of recommendations
     */
    private List<String> recommendations;
    
    /**
     * Whether the modification can proceed
     */
    private Boolean canProceed;
}
