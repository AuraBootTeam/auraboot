package com.auraboot.framework.exception;

import java.util.Map;

/** The caller submitted an expected version that no longer matches the target. */
public class CasVersionConflictException extends ConflictException {
    public CasVersionConflictException(String message, Map<String, Object> details) {
        super(ConflictCodes.CAS_VERSION_CONFLICT, message, details);
    }
}
