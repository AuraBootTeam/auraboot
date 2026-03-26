package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 验证警告信息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ValidationWarning {
    
    /**
     * 警告代码
     */
    private String code;
    
    /**
     * 警告消息
     */
    private String message;
    
    /**
     * 警告字段
     */
    private String field;
    
    /**
     * 警告值
     */
    private Object value;
    
    /**
     * 构造函数 - 仅消息
     */
    public ValidationWarning(String message) {
        this.message = message;
    }
    
    /**
     * 构造函数 - 代码和消息
     */
    public ValidationWarning(String code, String message) {
        this.code = code;
        this.message = message;
    }
    
    /**
     * 从字符串创建ValidationWarning
     */
    public static ValidationWarning fromString(String message) {
        return new ValidationWarning(message);
    }
}