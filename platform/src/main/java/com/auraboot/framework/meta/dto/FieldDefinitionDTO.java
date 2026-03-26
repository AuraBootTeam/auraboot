package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 字段定义DTO
 * 用于字段定义的数据传输
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldDefinitionDTO {
    
    /**
     * 字段PID
     */
    private String fieldPid;
    
    /**
     * 字段键
     */
    @NotBlank(message = "字段键不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "字段键必须以字母开头，只能包含字母、数字和下划线")
    private String code;
    
    /**
     * 字段名称
     */
    @NotBlank(message = "字段名称不能为空")
    @Size(max = 100, message = "字段名称长度不能超过100个字符")
    private String fieldName;
    
    /**
     * 数据类型
     */
    @NotBlank(message = "数据类型不能为空")
    private String dataType;
    
    /**
     * 长度
     */
    @Min(value = 0, message = "长度不能为负数")
    private Integer length;
    
    /**
     * 精度
     */
    @Min(value = 0, message = "精度不能为负数")
    private Integer precision;
    
    /**
     * 小数位数
     */
    @Min(value = 0, message = "小数位数不能为负数")
    private Integer scale;
    
    /**
     * 是否可为空
     */
    private Boolean nullable;
    
    /**
     * 默认值
     */
    private String defaultValue;
    
    /**
     * 描述
     */
    @Size(max = 500, message = "描述长度不能超过500个字符")
    private String description;
    
    /**
     * 显示顺序
     */
    @Min(value = 0, message = "显示顺序不能为负数")
    private Integer displayOrder;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 字段约束
     */
    private Map<String, Object> constraints;
    
    /**
     * 字段选项（用于枚举类型）
     */
    private Map<String, Object> options;
    
    /**
     * 字段验证规则
     */
    private Map<String, Object> validationRules;
    
    /**
     * 字段显示配置
     */
    private Map<String, Object> displayConfig;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
    
    /**
     * 创建人
     */
    private String createdBy;
    
    /**
     * 更新人
     */
    private String updatedBy;
    
    /**
     * 检查字段是否为必填
     */
    public boolean isRequired() {
        return nullable != null && !nullable;
    }
    
    /**
     * 检查字段是否为数值类型
     */
    public boolean isNumericType() {
        if (dataType == null) {
            return false;
        }
        String type = dataType.toUpperCase();
        return type.contains("int") || type.contains("decimal") || 
               type.contains("float") || type.contains("double") ||
               type.contains("numeric");
    }
    
    /**
     * 检查字段是否为字符串类型
     */
    public boolean isStringType() {
        if (dataType == null) {
            return false;
        }
        String type = dataType.toUpperCase();
        return type.contains("varchar") || type.contains("char") ||
               type.contains("text") || type.contains("string");
    }
    
    /**
     * 检查字段是否为日期时间类型
     */
    public boolean isDateTimeType() {
        if (dataType == null) {
            return false;
        }
        String type = dataType.toUpperCase();
        return type.contains("date") || type.contains("time") ||
               type.contains("timestamp");
    }
    
    /**
     * 检查字段是否激活
     */
    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }
    
    /**
     * 获取完整的字段类型描述
     */
    public String getFullDataType() {
        if (dataType == null) {
            return "unknown";
        }
        
        StringBuilder fullType = new StringBuilder(dataType);
        
        if (isStringType() && length != null && length > 0) {
            fullType.append("(").append(length).append(")");
        } else if (isNumericType() && precision != null && precision > 0) {
            fullType.append("(").append(precision);
            if (scale != null && scale > 0) {
                fullType.append(",").append(scale);
            }
            fullType.append(")");
        }
        
        return fullType.toString();
    }
}