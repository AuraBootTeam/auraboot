package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 字段验证结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class FieldValidationResult {
    
    /**
     * 验证是否通过
     */
    private Boolean valid;
    
    /**
     * 错误信息列表
     */
    private List<String> errors;
    
    /**
     * 警告信息列表
     */
    private List<String> warnings;
    
    /**
     * 字段名称
     */
    private String fieldName;
    
    /**
     * 验证的值
     */
    private Object value;
    
    // 便利方法
    public boolean isValid() {
        return Boolean.TRUE.equals(valid);
    }
    
    public static FieldValidationResult invalid(String error) {
        return FieldValidationResult.builder()
                .valid(false)
                .errors(List.of(error))
                .build();
    }
    
    public static FieldValidationResult valid() {
        return FieldValidationResult.builder()
                .valid(true)
                .build();
    }
}