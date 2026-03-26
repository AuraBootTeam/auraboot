package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 页面统计
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageStatistics {
    
    private Long totalPages;
    
    private Long publishedPages;
    
    private Long draftPages;
    
    private Long totalViews;
    
    private Long totalUsers;
    
    private Double averageViewTime;
    
    private Map<String, Long> viewsByPage;
    
    private Map<String, Long> viewsByDate;
    
    private Map<String, Long> usersByPage;
    
    private String period;
    

}