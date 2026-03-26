package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import java.util.Map;

/**
 * 实体字段配置更新请求
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityFieldConfigUpdateRequest {
    
    /**
     * 是否必填
     */
    private Boolean required;
    
    /**
     * 是否唯一
     */
    private Boolean unique;
    
    /**
     * 排序顺序
     */
    @Min(value = 0, message = "排序顺序不能小于0")
    @Max(value = 9999, message = "排序顺序不能大于9999")
    private Integer sortOrder;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 扩展配置
     */
    private Map<String, Object> extendedConfig;
    
    /**
     * 更新人
     */
    private String updatedBy;
    
    /**
     * 检查是否有任何字段需要更新
     */
    public boolean hasUpdates() {
        return required != null || unique != null || sortOrder != null ||
               status != null || extendedConfig != null;
    }
    
    /**
     * 检查是否更新了约束条件
     */
    public boolean hasConstraintUpdates() {
        return required != null || unique != null;
    }
    
    /**
     * 检查是否更新了显示配置
     */
    public boolean hasDisplayUpdates() {
        return sortOrder != null || extendedConfig != null;
    }
}