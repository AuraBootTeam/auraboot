package com.auraboot.framework.exception;

import com.auraboot.framework.common.constant.ResponseCode;

/**
 * 业务异常类
 */
public class BusinessException extends RootUnCheckedException {

    private static final long serialVersionUID = -4628485572389136720L;

    public BusinessException(String message) {
        super(ResponseCode.BUSINESS_ERROR, message);
    }

    public BusinessException(ResponseCode responseCode) {
        super(responseCode);
    }

    public BusinessException(ResponseCode responseCode, Object context) {
        super(responseCode, context);
    }

    public BusinessException(String message, Throwable cause) {
        super(ResponseCode.BUSINESS_ERROR, cause);
    }
}