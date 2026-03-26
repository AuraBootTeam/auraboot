package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 验证错误信息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ValidationError {
    
    /**
     * 错误代码
     */
    private String code;
    
    /**
     * 错误消息
     */
    private String message;
    
    /**
     * 错误字段
     */
    private String field;
    
    /**
     * 错误值
     */
    private Object value;
    
    /**
     * 构造函数 - 仅消息
     */
    public ValidationError(String message) {
        this.message = message;
    }
    
    /**
     * 构造函数 - 代码和消息
     */
    public ValidationError(String code, String message) {
        this.code = code;
        this.message = message;
    }
    
    /**
     * 从字符串创建ValidationError
     */
    public static ValidationError fromString(String message) {
        return new ValidationError(message);
    }
}