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

    /**
     * Construct with explicit ResponseCode, human-readable message, and root cause.
     * Use this when wrapping a low-level exception (DB, IO, parse, etc.) into a
     * BusinessException so the cause chain remains intact for
     * {@code GlobalExceptionHandler} and observability.
     *
     * <p>The other constructors either drop the message (the {@code (String,
     * Throwable)} ctor) or drop the cause (the {@code (ResponseCode, Object)}
     * ctor when context is a String). Prefer this one for §P4 wrap-and-rethrow
     * — see {@code docs/standards/core/catch-exception-pattern.md}.
     */
    public BusinessException(ResponseCode responseCode, String message, Throwable cause) {
        super(responseCode, message);
        initCause(cause);
    }
}