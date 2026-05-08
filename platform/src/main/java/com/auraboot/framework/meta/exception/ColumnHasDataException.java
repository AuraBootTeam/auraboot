package com.auraboot.framework.meta.exception;

/**
 * Thrown when attempting to remove a column that still contains data and
 * the caller asked for a safe (refuseIfDataExists=true) removal.
 *
 * <p>Mirrors {@link MetaServiceException} ctor shape for consistency with
 * sibling exceptions in this package. Caught at the {@code field:remove}
 * skill / REST layer to surface a user-facing "column not empty" message
 * instead of a generic 500.</p>
 */
public class ColumnHasDataException extends RuntimeException {

    public ColumnHasDataException(String message) {
        super(message);
    }

    public ColumnHasDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
