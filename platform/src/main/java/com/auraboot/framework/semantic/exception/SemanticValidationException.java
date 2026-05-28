package com.auraboot.framework.semantic.exception;

/**
 * Raised by {@code SemanticValidator} for business rule violations that go beyond
 * what JSON Schema can express:
 *
 * <ul>
 *   <li>{@code SQL_INJECTION_DETECTED} — denylist hit in {@code metric.filter} or {@code access_policy.sql_filter}</li>
 *   <li>{@code DUPLICATE_CODE} — two metrics / dimensions / entities share the same code</li>
 *   <li>{@code MULTIPLE_PRIMARY_TIME} — more than one dimension declares {@code primary_time: true}</li>
 *   <li>{@code MISSING_REFERENCE} — metric references unknown measure, derived expr references unknown metric, etc.</li>
 *   <li>{@code ENTITY_TYPE_INVALID} — multiple {@code primary} entities, or {@code primary_entity} not declared</li>
 *   <li>{@code MEASURE_MISSING_EXPR_OR_FIELD} — measure has neither {@code field_ref} nor {@code expr}</li>
 * </ul>
 *
 * <p>Maps to HTTP 400 in {@code SemanticController}.
 */
public class SemanticValidationException extends RuntimeException {

    private final String errorCode;

    public SemanticValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
