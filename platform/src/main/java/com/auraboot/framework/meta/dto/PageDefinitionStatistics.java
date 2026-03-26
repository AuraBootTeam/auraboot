package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 页面定义统计
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageDefinitionStatistics {
    
    private Long totalCount;
    
    private Long activeCount;
    
    private Long inactiveCount;
    
    private Long draftCount;
    
    private Map<String, Long> countByType;
    
    private Map<String, Long> countByCategory;
    
    private Map<String, Long> countByStatus;
    
    private Long publishedCount;
    
    private Map<String, Long> typeDistribution;
    
    private Long tenantId;
    

}