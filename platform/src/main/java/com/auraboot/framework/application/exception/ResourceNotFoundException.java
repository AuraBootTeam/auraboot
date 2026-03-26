package com.auraboot.framework.application.exception;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;

/**
 * Resource Not Found Exception
 * 
 * <p>Thrown when a requested resource does not exist.
 * 
 * @author AuraBoot Platform
 * @since V4
 */
public class ResourceNotFoundException extends ValidationException {
    
    public ResourceNotFoundException(String message) {
        super(ResponseCode.CommonValidationFailed, message);
    }
    
    public ResourceNotFoundException(ResponseCode code, String message) {
        super(code, message);
    }
}
