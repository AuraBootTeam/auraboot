package com.auraboot.framework.chatbi.v2.compiler;

/**
 * Thrown by {@link TokenCompiler} when a Token cannot be mapped to a
 * {@code SemanticQueryRequest} slot. PRD 17 §6.3 error codes.
 *
 * <p>{@code code} is the stable wire identifier (e.g.
 * {@code UNKNOWN_METRIC}, {@code UNKNOWN_DIMENSION}, {@code BAD_TIME_RANGE});
 * downstream controllers translate it into a 400 + i18n message.
 */
public class TokenCompileException extends RuntimeException {

    private final String code;

    public TokenCompileException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
