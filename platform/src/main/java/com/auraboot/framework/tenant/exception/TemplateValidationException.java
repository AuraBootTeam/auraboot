package com.auraboot.framework.tenant.exception;

/**
 * 模板验证异常
 * 
 * @author AuraBoot
 * @since 2.2.0
 */
public class TemplateValidationException extends RuntimeException {
    
    public TemplateValidationException(String message) {
        super(message);
    }
    
    public TemplateValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
