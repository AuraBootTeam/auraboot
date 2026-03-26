package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 排序字段
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class SortField {
    
    /**
     * 字段名
     */
    private String fieldName;
    
    /**
     * 排序方向
     */
    @Builder.Default
    private SortDirection direction = SortDirection.ASC;
    
    /**
     * 排序优先级
     */
    @Builder.Default
    private Integer priority = 0;
    
    public enum SortDirection {
        ASC, DESC
    }
}