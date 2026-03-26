package com.auraboot.framework.meta.exception;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import lombok.Getter;

/**
 * Meta 模块统一业务异常
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Getter
public class MetaBusinessException extends RootUnCheckedException {
    
    private final MetaErrorCode errorCode;
    
    public MetaBusinessException(MetaErrorCode errorCode) {
        super(ResponseCode.BUSINESS_ERROR, errorCode.getMessage());
        this.errorCode = errorCode;
    }
    
    public MetaBusinessException(MetaErrorCode errorCode, String detail) {
        super(ResponseCode.BUSINESS_ERROR, errorCode.getMessage() + ": " + detail);
        this.errorCode = errorCode;
    }
    
    public MetaBusinessException(MetaErrorCode errorCode, Throwable cause) {
        super(ResponseCode.BUSINESS_ERROR, cause);
        this.errorCode = errorCode;
    }
    
    public MetaBusinessException(MetaErrorCode errorCode, String detail, Throwable cause) {
        super(ResponseCode.BUSINESS_ERROR, new RuntimeException(errorCode.getMessage() + ": " + detail, cause));
        this.errorCode = errorCode;
    }
}
