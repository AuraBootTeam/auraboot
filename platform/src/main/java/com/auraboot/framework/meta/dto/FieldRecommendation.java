package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Field recommendation DTO
 * Used for recommending fields when binding to models
 */
@Data
@Builder
public class FieldRecommendation {
    
    /**
     * Recommended field
     */
    private MetaFieldDTO field;
    
    /**
     * Usage count across all models
     */
    private Integer usageCount;
    
    /**
     * Relevance score (0-1) based on semantic similarity
     */
    private Double relevanceScore;
    
    /**
     * Reason for recommendation
     */
    private String recommendationReason;
    
    /**
     * List of model codes using this field
     */
    private List<String> usedByModels;
}
