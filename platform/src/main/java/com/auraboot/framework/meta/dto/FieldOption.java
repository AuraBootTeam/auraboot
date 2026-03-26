package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

/**
 * 字段选项
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class FieldOption {
    
    /**
     * 选项值
     */
    private Object value;
    
    /**
     * 显示标签
     */
    private String label;
    
    /**
     * 选项描述
     */
    private String description;
    
    /**
     * 是否禁用
     */
    @Builder.Default
    private Boolean disabled = false;
    
    /**
     * 选项分组
     */
    private String group;
    
    /**
     * 排序权重
     */
    @Builder.Default
    private Integer sortOrder = 0;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extraProps;
}