package com.auraboot.framework.exception;

import java.util.Map;

/** The same client request key was reused with a different durable intent. */
public class RequestIntentConflictException extends ConflictException {
    public RequestIntentConflictException(String message, Map<String, Object> details) {
        super(ConflictCodes.REQUEST_INTENT_CONFLICT, message, details);
    }
}
