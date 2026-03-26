package com.auraboot.framework.meta.exception;

import com.auraboot.framework.meta.dto.ValidationResult;
import lombok.Getter;

/**
 * 数据验证异常
 * 
 * 当数据验证失败时抛出此异常，触发事务回滚
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Getter
public class ValidationException extends MetaServiceException {

    private final ValidationResult validationResult;

    public ValidationException(String message) {
        super(message);
        this.validationResult = null;
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
        this.validationResult = null;
    }

    public ValidationException(String message, ValidationResult validationResult) {
        super(message);
        this.validationResult = validationResult;
    }

    public ValidationException(String message, ValidationResult validationResult, Throwable cause) {
        super(message, cause);
        this.validationResult = validationResult;
    }
}
