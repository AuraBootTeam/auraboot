package com.auraboot.framework.meta.exception;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Meta API exception handler for meta-controller-specific exceptions.
 *
 * Handles only exceptions that are unique to the Meta module.
 * Common exceptions (validation, binding, access denied, generic) are
 * handled by GlobalExceptionHandler to avoid duplication and priority conflicts.
 *
 * @author AuraBoot Framework
 * @since 2.1.0
 */
@Slf4j
@Order(1)
@RestControllerAdvice(basePackages = "com.auraboot.framework.meta.controller")
public class MetaApiExceptionHandler {

    /**
     * Handle Meta service exceptions (business logic errors).
     */
    @ExceptionHandler(MetaServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleMetaServiceException(MetaServiceException e) {
        log.warn("Meta service exception: {}", e.getMessage());

        ApiResponse<Void> response = ApiResponse.error(ResponseCode.BUSINESS_ERROR, e.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle security exceptions — do not expose internal details.
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurityException(SecurityException e) {
        log.error("Security exception: {}", e.getMessage(), e);

        ApiResponse<Void> response = ApiResponse.error(ResponseCode.FORBIDDEN, "Access denied");

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * Handle SQL exception — do not expose database details.
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(
            org.springframework.dao.DataAccessException e) {

        log.error("Data access exception: {}", e.getMessage(), e);

        ApiResponse<Void> response = ApiResponse.error(
            ResponseCode.SystemError, "An unexpected error occurred. Please try again later.");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Handle duplicate idempotent request (HTTP 409 Conflict).
     */
    @ExceptionHandler(IdempotentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdempotentException(IdempotentException e) {
        log.warn("Idempotent request rejected: {}", e.getMessage());

        ApiResponse<Void> response = ApiResponse.error(HttpStatus.CONFLICT.value(), e.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Handle SQL injection detection — log for security audit.
     */
    @ExceptionHandler(SqlInjectionException.class)
    public ResponseEntity<ApiResponse<Void>> handleSqlInjectionException(SqlInjectionException e) {
        log.error("SQL injection detected: {}", e.getMessage(), e);

        ApiResponse<Void> response = ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "Invalid query parameters");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle column-has-data refusal (Spec §3.7 #6 / §4) — HTTP 422.
     *
     * <p>Surfaced when {@code MetaFieldService.removeFromModel} is invoked with
     * {@code refuseIfDataExists=true} and the target column still carries
     * non-null rows. Wire code is the literal string {@code COLUMN_HAS_DATA}
     * so the FE / CLI can switch on it without depending on numeric codes.
     */
    @ExceptionHandler(ColumnHasDataException.class)
    public ResponseEntity<ApiResponse<Void>> handleColumnHasDataException(ColumnHasDataException e) {
        log.warn("Column has data — remove refused: {}", e.getMessage());

        ApiResponse<Void> response = ApiResponse.error(
                HttpStatus.UNPROCESSABLE_ENTITY.value(), e.getMessage());
        // Override the numeric default (422) with the wire-stable string code.
        response.setCode("COLUMN_HAS_DATA");

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
    }

    /**
     * Handle query timeout.
     */
    @ExceptionHandler(QueryTimeoutException.class)
    public ResponseEntity<ApiResponse<Void>> handleQueryTimeoutException(QueryTimeoutException e) {
        log.warn("Query timeout: {}", e.getMessage());

        ApiResponse<Void> response = ApiResponse.error(
            HttpStatus.REQUEST_TIMEOUT.value(), "Query timeout. Please refine your query and try again.");

        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(response);
    }
}
