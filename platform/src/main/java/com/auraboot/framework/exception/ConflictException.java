package com.auraboot.framework.exception;

import java.util.Collections;
import java.util.Map;

/**
 * Thrown when an optimistic lock conflict is detected (HTTP 409 Conflict).
* Example: saving a page schema with an outdated row_version.
*/
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String conflictCode;
    private final Map<String, Object> details;

    public ConflictException(String message) {
        this(ConflictCodes.GENERIC_CONFLICT, message, Map.of());
    }

    public ConflictException(String message, Throwable cause) {
        this(ConflictCodes.GENERIC_CONFLICT, message, Map.of(), cause);
    }

    public ConflictException(String conflictCode, String message, Map<String, Object> details) {
        this(conflictCode, message, details, null);
    }

    public ConflictException(
            String conflictCode,
            String message,
            Map<String, Object> details,
            Throwable cause) {
        super(message, cause);
        this.conflictCode = conflictCode == null || conflictCode.isBlank()
                ? ConflictCodes.GENERIC_CONFLICT : conflictCode;
        this.details = details == null ? Map.of() : Collections.unmodifiableMap(details);
    }

    public String getConflictCode() {
        return conflictCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    /** Stable, non-customer-facing conflict codes consumed by API clients and the UI. */
    public static final class ConflictCodes {
        public static final String GENERIC_CONFLICT = "GENERIC_CONFLICT";
        public static final String CAS_VERSION_REQUIRED = "CAS_VERSION_REQUIRED";
        public static final String CAS_VERSION_CONFLICT = "CAS_VERSION_CONFLICT";
        public static final String REQUEST_INTENT_CONFLICT = "REQUEST_INTENT_CONFLICT";

        private ConflictCodes() {
        }
    }
}
