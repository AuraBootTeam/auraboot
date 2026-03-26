package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import java.util.Map;

/**
 * 字段定义更新请求
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldDefinitionUpdateRequest {
    
    /**
     * 字段名称
     */
    @Size(max = 100, message = "字段名称长度不能超过100个字符")
    private String fieldName;
    
    /**
     * 字段描述
     */
    @Size(max = 500, message = "字段描述长度不能超过500个字符")
    private String description;
    
    /**
     * 数据类型
     */
    private String dataType;
    
    /**
     * 字段长度
     */
    @Min(value = 0, message = "字段长度不能小于0")
    @Max(value = 65535, message = "字段长度不能大于65535")
    private Integer fieldLength;
    
    /**
     * 小数位数
     */
    @Min(value = 0, message = "小数位数不能小于0")
    @Max(value = 30, message = "小数位数不能大于30")
    private Integer decimalPlaces;
    
    /**
     * 是否必填
     */
    private Boolean required;
    
    /**
     * 是否唯一
     */
    private Boolean unique;
    
    /**
     * 是否可索引
     */
    private Boolean indexable;
    
    /**
     * 默认值
     */
    private String defaultValue;
    
    /**
     * 验证规则
     */
    private Map<String, Object> validationRules;
    
    /**
     * 显示配置
     */
    private Map<String, Object> displayConfig;
    
    /**
     * 排序顺序
     */
    private Integer sortOrder;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 更新人
     */
    private String updatedBy;
    
    /**
     * 检查是否有任何字段需要更新
     */
    public boolean hasUpdates() {
        return fieldName != null || description != null || dataType != null ||
               fieldLength != null || decimalPlaces != null || required != null ||
               unique != null || indexable != null || defaultValue != null ||
               validationRules != null || displayConfig != null || sortOrder != null ||
               status != null;
    }
    
    /**
     * 检查是否为数值类型
     */
    public boolean isNumericType() {
        if (dataType == null) return false;
        return "integer".equals(dataType) || "decimal".equals(dataType) || 
               "float".equals(dataType) || "double".equals(dataType);
    }
    
    /**
     * 检查是否为字符串类型
     */
    public boolean isStringType() {
        if (dataType == null) return false;
        return "string".equals(dataType) || "text".equals(dataType);
    }
}