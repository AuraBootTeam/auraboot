package com.auraboot.framework.meta.exception;

/**
 * Meta服务异常
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
public class MetaServiceException extends RuntimeException {

    public MetaServiceException(String message) {
        super(message);
    }

    public MetaServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    public MetaServiceException(Throwable cause) {
        super(cause);
    }
}