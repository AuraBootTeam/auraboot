package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import java.util.Map;

/**
 * 字段定义创建请求
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldDefinitionCreateRequest {
    
    /**
     * 租户ID
     */
    @NotBlank(message = "租户ID不能为空")
    private String tenantId;
    
    /**
     * 实体PID
     */
    @NotBlank(message = "实体PID不能为空")
    private String entityPid;
    
    /**
     * 字段键
     */
    @NotBlank(message = "字段键不能为空")
    @Size(max = 50, message = "字段键长度不能超过50个字符")
    private String code;
    
    /**
     * 字段名称
     */
    @NotBlank(message = "字段名称不能为空")
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
    @NotBlank(message = "数据类型不能为空")
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
    @NotNull(message = "是否必填不能为空")
    private Boolean required;
    
    /**
     * 是否唯一
     */
    private Boolean unique = false;
    
    /**
     * 是否可索引
     */
    private Boolean indexable = true;
    
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
    private Integer sortOrder = 0;
    
    /**
     * 状态
     */
    private String status = "active";
    
    /**
     * 创建人
     */
    private String createdBy;
    
    /**
     * 检查是否为数值类型
     */
    public boolean isNumericType() {
        return "integer".equals(dataType) || "decimal".equals(dataType) || 
               "float".equals(dataType) || "double".equals(dataType);
    }
    
    /**
     * 检查是否为字符串类型
     */
    public boolean isStringType() {
        return "string".equals(dataType) || "text".equals(dataType);
    }
    
    /**
     * 检查是否需要长度限制
     */
    public boolean needsLengthLimit() {
        return isStringType() && fieldLength != null && fieldLength > 0;
    }
}