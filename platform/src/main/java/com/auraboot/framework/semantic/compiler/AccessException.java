package com.auraboot.framework.semantic.compiler;

import lombok.Getter;

/**
 * Thrown by {@link AccessPolicyCompiler} when an access policy cannot be
 * compiled into a safe parameterised WHERE clause for the current user.
 *
 * <p>Distinct from {@link MetricCompileException} because callers may want to
 * return HTTP 403 ({@code SEMANTIC_PERMISSION_DENIED} / {@code RLS_FILTERED_OUT})
 * rather than 400.
 *
 * <p>Stable error codes:
 * <ul>
 *   <li>{@code USER_ATTRIBUTE_MISSING} — policy references {@code {user.X}} but user has no value for X</li>
 *   <li>{@code SQL_INJECTION_DETECTED} — sql_filter contains a denylisted token (defence-in-depth)</li>
 *   <li>{@code UNRESOLVED_PLACEHOLDER} — sql_filter has a placeholder we cannot interpret</li>
 * </ul>
 */
@Getter
public class AccessException extends RuntimeException {

    private final String errorCode;

    public AccessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
