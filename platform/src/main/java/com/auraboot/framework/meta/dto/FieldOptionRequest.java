package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

/**
 * 字段选项请求
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class FieldOptionRequest {
    
    /**
     * 搜索关键词
     */
    private String keyword;
    
    /**
     * 限制返回数量
     */
    @Builder.Default
    private Integer limit = 50;
    
    /**
     * 偏移量
     */
    @Builder.Default
    private Integer offset = 0;
    
    /**
     * 是否包含禁用选项
     */
    @Builder.Default
    private Boolean includeDisabled = false;
    
    /**
     * 选项分组过滤
     */
    private String group;
    
    /**
     * 扩展过滤条件
     */
    private Map<String, Object> filters;
}