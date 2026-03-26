package com.auraboot.framework.tenant.exception;

/**
 * 模板文件不存在异常
 * 
 * @author AuraBoot
 * @since 2.2.0
 */
public class TemplateNotFoundException extends RuntimeException {
    
    public TemplateNotFoundException(String message) {
        super(message);
    }
    
    public TemplateNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
