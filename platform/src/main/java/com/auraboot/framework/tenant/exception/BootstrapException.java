package com.auraboot.framework.tenant.exception;

/**
 * 租户初始化异常
 * 
 * @author AuraBoot
 * @since 2.2.0
 */
public class BootstrapException extends RuntimeException {
    
    public BootstrapException(String message) {
        super(message);
    }
    
    public BootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
