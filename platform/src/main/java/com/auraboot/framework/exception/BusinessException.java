package com.auraboot.framework.exception;

import com.auraboot.framework.common.constant.ResponseCode;

/**
 * 业务异常类
 */
public class BusinessException extends RootUnCheckedException {

    private static final long serialVersionUID = -4628485572389136720L;

    /** Substitution args for a parameterized {@code $i18n:<key>} message; null when not used. */
    private transient Object[] i18nArgs;

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

    /**
     * Parameterized i18n business error. The message is stored as {@code $i18n:<key>} and the
     * args are carried for {@code {0}}-style substitution at the response boundary
     * ({@code GlobalExceptionHandler}), where the request locale is known. The service layer has
     * no locale, so it emits the key + values; resolution happens on the way out. Mirrors Spring
     * MessageSource semantics over the platform i18n catalog (see {@code I18nService#getMessage}).
     *
     * <p>Example: {@code throw BusinessException.i18n("tenant.member.not_found", memberId)} with
     * catalog entry {@code 成员不存在: {0}} / {@code Member not found: {0}}.
     */
    public static BusinessException i18n(String key, Object... args) {
        BusinessException ex = new BusinessException("$i18n:" + key);
        ex.i18nArgs = (args == null || args.length == 0) ? null : args;
        return ex;
    }

    /** Substitution args for a parameterized {@code $i18n:} message, or null. */
    public Object[] getI18nArgs() {
        return i18nArgs;
    }
}