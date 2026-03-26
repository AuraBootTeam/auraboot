package com.auraboot.framework.exception;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.meta.dto.SodCheckResult;
import lombok.Getter;

/**
 * Exception thrown when a Separation of Duties (SoD) check fails
 * with HARD enforcement, blocking command execution.
 *
 * @author AuraBoot Team
 * @since 6.2.0
 */
@Getter
public class SodViolationException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final transient SodCheckResult checkResult;

    public SodViolationException(String message, SodCheckResult checkResult) {
        super(ResponseCode.FORBIDDEN, "SoD violation: " + message);
        this.checkResult = checkResult;
    }
}
