package com.auraboot.framework.aurabot.skill.error;

import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.common.constant.ResponseCode;

/**
 * Dedicated runtime exception for AuraBot Skill SPI request validation
 * (Plan Step 6 / SPI Contract §11).
 *
 * <p>{@link com.auraboot.framework.exception.BusinessException} only accepts
 * the closed {@link ResponseCode} enum, which has no entries for the eight
 * skill-specific codes (each maps to its own HTTP status). Adding new ctors
 * to {@code BusinessException} is forbidden by the project red-line, so a
 * thin sibling exception is the cleanest path: it carries the typed
 * {@link SkillErrorCode} verbatim, and the controller-layer
 * {@code @ExceptionHandler} (Step 7) maps it to the wire envelope.
 *
 * <p>{@code fieldPath} is optional and only populated for
 * {@link SkillErrorCode#PARAMS_INVALID} so the FE can surface JSON-Pointer
 * style errors against the offending param.
 */
public class SkillSpiException extends RootUnCheckedException {

    private static final long serialVersionUID = 1L;

    private final SkillErrorCode errorCode;
    private final String fieldPath;

    public SkillSpiException(SkillErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public SkillSpiException(SkillErrorCode errorCode, String message, String fieldPath) {
        this(errorCode, message, fieldPath, null);
    }

    public SkillSpiException(SkillErrorCode errorCode, String message, String fieldPath, Throwable cause) {
        // RootUnCheckedException requires a ResponseCode; supply BUSINESS_ERROR
        // as the placeholder envelope — the wire serialiser will use
        // {@link #getErrorCode()} for the actual skill-error code instead.
        super(ResponseCode.BUSINESS_ERROR, message);
        this.errorCode = errorCode;
        this.fieldPath = fieldPath;
        if (cause != null) {
            initCause(cause);
        }
    }

    public SkillErrorCode getErrorCode() {
        return errorCode;
    }

    public String getFieldPath() {
        return fieldPath;
    }
}
