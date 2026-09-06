package com.auraboot.framework.meta.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a record mutation arrives with a stale optimistic-version
 * precondition (payload {@code _expectedVersion} or the
 * {@code X-Base-Record-Version} header) and the compare-and-swap update
 * matches zero rows.
 *
 * <p>Extends {@link MetaServiceException} so existing callers that already
 * characterize version conflicts keep working; the dedicated handler renders
 * it as HTTP 409 with wire code {@code 40900} so mobile offline replay can
 * branch into the conflict-resolution flow instead of treating the failure
 * as a generic business error.</p>
 */
public class RecordVersionConflictException extends MetaServiceException {

    /** Wire-stable string code consumed by mobile clients ({@code 40900}). */
    public static final String CODE = "40900";

    public RecordVersionConflictException(String message) {
        super(message);
    }

    public RecordVersionConflictException(String message, Throwable cause) {
        super(message, cause);
    }

    /** HTTP status this exception renders as. */
    public static HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
