package com.auraboot.framework.meta.exception;

/**
 * Thrown when a temporal field value cannot be parsed to the expected type.
 * Maps to HTTP 400 Bad Request via GlobalExceptionHandler.
 */
public class TemporalParseException extends RuntimeException {

    private final String field;
    private final String rawValue;
    private final String expected;

    public TemporalParseException(String field, String rawValue, String expected) {
        super(String.format(
            "Field '%s' value '%s' cannot be parsed. Expected: %s",
            field, rawValue, expected
        ));
        this.field = field;
        this.rawValue = rawValue;
        this.expected = expected;
    }

    public String getField()    { return field; }
    public String getRawValue() { return rawValue; }
    public String getExpected() { return expected; }
}
