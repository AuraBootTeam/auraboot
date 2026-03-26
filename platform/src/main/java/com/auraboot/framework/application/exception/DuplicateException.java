package com.auraboot.framework.application.exception;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;

/**
 * Duplicate Exception
 * 
 * <p>Thrown when attempting to create a resource that already exists.
 * 
 * @author AuraBoot Platform
 * @since V4
 */
public class DuplicateException extends ValidationException {
    
    public DuplicateException(String message) {
        super(ResponseCode.CommonValidationFailed, message);
    }
    
    public DuplicateException(ResponseCode code, String message) {
        super(code, message);
    }
}
