package com.auraboot.framework.exception;

import java.util.Map;

/** A strict existing-target mutation omitted the version observed by the caller. */
public class CasVersionRequiredException extends ConflictException {
    public CasVersionRequiredException(String message, Map<String, Object> details) {
        super(ConflictCodes.CAS_VERSION_REQUIRED, message, details);
    }
}
