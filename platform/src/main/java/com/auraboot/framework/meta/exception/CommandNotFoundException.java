package com.auraboot.framework.meta.exception;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;

/** Signals an absent command without classifying unrelated read failures as absence. */
public final class CommandNotFoundException extends BusinessException {
    private static final long serialVersionUID = 1L;

    public CommandNotFoundException(String code) {
        super(ResponseCode.BadParam, "Command not found: " + code);
    }
}
