package com.auraboot.framework.tenant.exception;

/**
 * 模板解析异常
 * 
 * @author AuraBoot
 * @since 2.2.0
 */
public class TemplateParseException extends RuntimeException {
    
    public TemplateParseException(String message) {
        super(message);
    }
    
    public TemplateParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
