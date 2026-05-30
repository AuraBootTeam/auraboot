package com.auraboot.framework.dataquality.ge;

/**
 * Thrown by {@link ExpectationsParser} when an expectation JSON cannot be parsed
 * or references an unsupported {@code expectation_type}.
 *
 * <p>Modelled after
 * {@link com.auraboot.framework.chatbi.v2.compiler.TokenCompileException}:
 * a stable {@code code} string + human-readable {@code message}.
 *
 * <p>Example codes:
 * <ul>
 *   <li>{@code UNKNOWN_EXPECTATION_TYPE} — expectation_type not in the supported set</li>
 *   <li>{@code MISSING_KWARGS} — required kwargs field absent</li>
 *   <li>{@code MALFORMED_JSON} — JSON parse error</li>
 * </ul>
 */
public class ExpectationParseException extends RuntimeException {

    private final String code;

    public ExpectationParseException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Stable wire identifier for error classification. */
    public String code() {
        return code;
    }
}
