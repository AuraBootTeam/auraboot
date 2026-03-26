package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

/**
 * 实体字段统计信息DTO
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityFieldStatisticsDTO {
    
    /**
     * 实体PID
     */
    private String entityPid;
    
    /**
     * 实体名称
     */
    private String entityName;
    
    /**
     * 租户ID
     */
    private String tenantId;
    
    /**
     * 总字段数
     */
    private Integer totalFields;
    
    /**
     * 必填字段数
     */
    private Integer requiredFields;
    
    /**
     * 唯一字段数
     */
    private Integer uniqueFields;
    
    /**
     * 激活字段数
     */
    private Integer activeFields;
    
    /**
     * 草稿字段数
     */
    private Integer draftFields;
    
    /**
     * 按数据类型分组的字段数量
     */
    private Map<String, Integer> fieldsByDataType;
    
    /**
     * 按状态分组的字段数量
     */
    private Map<String, Integer> fieldsByStatus;
    
    /**
     * 最大排序顺序
     */
    private Integer maxSortOrder;
    
    /**
     * 最小排序顺序
     */
    private Integer minSortOrder;
    
    /**
     * 获取必填字段比例
     */
    public double getRequiredFieldsRatio() {
        if (totalFields == null || totalFields == 0) {
            return 0.0;
        }
        return (double) (requiredFields != null ? requiredFields : 0) / totalFields;
    }
    
    /**
     * 获取唯一字段比例
     */
    public double getUniqueFieldsRatio() {
        if (totalFields == null || totalFields == 0) {
            return 0.0;
        }
        return (double) (uniqueFields != null ? uniqueFields : 0) / totalFields;
    }
    
    /**
     * 获取激活字段比例
     */
    public double getActiveFieldsRatio() {
        if (totalFields == null || totalFields == 0) {
            return 0.0;
        }
        return (double) (activeFields != null ? activeFields : 0) / totalFields;
    }
    
    /**
     * 检查是否有字段
     */
    public boolean hasFields() {
        return totalFields != null && totalFields > 0;
    }
    
    /**
     * 检查是否有必填字段
     */
    public boolean hasRequiredFields() {
        return requiredFields != null && requiredFields > 0;
    }
    
    /**
     * 检查是否有唯一字段
     */
    public boolean hasUniqueFields() {
        return uniqueFields != null && uniqueFields > 0;
    }
}