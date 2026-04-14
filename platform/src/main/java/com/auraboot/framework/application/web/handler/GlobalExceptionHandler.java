package com.auraboot.framework.application.web.handler;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.exception.PermissionDeniedException;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.exception.TemporalParseException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler for REST API endpoints.
 *
 * Returns proper HTTP status codes:
 * - 400 Bad Request: Validation errors, bad parameters
 * - 403 Forbidden: Permission denied
 * - 422 Unprocessable Entity: Business validation failures
 * - 500 Internal Server Error: Unexpected errors
 *
 * Order(100) ensures module-specific handlers (e.g. MetaApiExceptionHandler)
 * take priority for their scoped packages.
 */
@Slf4j
@Order(100)
@ControllerAdvice
public class GlobalExceptionHandler {

    @Value("${spring.profiles.active:prod}")
    private String activeProfile;

    private boolean isDevEnvironment() {
        return activeProfile.contains("dev") || activeProfile.contains("local");
    }

    /**
     * Build error detail map for dev environments.
     * Returns exception class + message so developers can diagnose faster.
     * In production, returns null (no detail exposed).
     */
    private Map<String, String> buildDevDetail(Exception ex) {
        if (!isDevEnvironment()) {
            return null;
        }
        Map<String, String> detail = new HashMap<>();
        detail.put("exception", ex.getClass().getSimpleName());
        detail.put("detail", ex.getMessage());
        if (ex.getCause() != null) {
            detail.put("cause", ex.getCause().getClass().getSimpleName() + ": " + ex.getCause().getMessage());
        }
        return detail;
    }

    /**
     * Handle permission access denied exceptions.
     * Returns HTTP 403 Forbidden.
     */
    @ExceptionHandler(PermissionDeniedException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handlePermissionDeniedException(PermissionDeniedException ex) {
        log.warn("Permission check failed: {} {}", ex.getPermissionCode(), ex.getMessage());

        ApiResponse<Object> response = ApiResponse.errorWithContext(ex.getResponseCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * Handle authentication failures (invalid username or password).
     * Returns HTTP 401 Unauthorized.
     *
     * This is an expected business scenario, not a system error,
     * so we log at WARN level without stack trace.
     */
    @ExceptionHandler({BadCredentialsException.class, UsernameNotFoundException.class})
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleBadCredentialsException(BadCredentialsException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());

        ApiResponse<Object> response = ApiResponse.errorWithContext(
            ResponseCode.InvalidUserNameOrPassword,
            ResponseCode.InvalidUserNameOrPassword.getDesc()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        // 客户端断开连接是正常行为，不需要记录为 ERROR
        log.debug("Client disconnected from async request: {}", ex.getMessage());
    }

    /**
     * Handle @Valid annotation validation failures.
     * Returns HTTP 400 Bad Request.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.warn("Parameter validation failed: {}", errors);
        ApiResponse<Map<String, String>> response = ApiResponse.errorWithContext(ResponseCode.BadParam, errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle @Validated annotation constraint violations.
     * Returns HTTP 400 Bad Request.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, String>>> handleConstraintViolationException(
            ConstraintViolationException ex) {
        Map<String, String> errors = ex.getConstraintViolations()
                .stream()
                .collect(Collectors.toMap(
                        violation -> violation.getPropertyPath().toString(),
                        ConstraintViolation::getMessage,
                        (existing, replacement) -> existing
                ));

        log.error("Constraint violation: {}", errors);
        ApiResponse<Map<String, String>> response = ApiResponse.errorWithContext(ResponseCode.BadParam, errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle form binding exceptions.
     * Returns HTTP 400 Bad Request.
     */
    @ExceptionHandler(BindException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, String>>> handleBindException(BindException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.error("Form binding failed: {}", errors);
        ApiResponse<Map<String, String>> response = ApiResponse.errorWithContext(ResponseCode.BadParam, errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle temporal field parse errors.
     * Returns HTTP 400 Bad Request with field/value/expected context.
     */
    @ExceptionHandler(TemporalParseException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleTemporalParseException(TemporalParseException ex) {
        log.warn("Temporal parse error: field={}, value={}, expected={}",
            ex.getField(), ex.getRawValue(), ex.getExpected());
        String message = String.format("Field '%s': invalid temporal value '%s'. Expected: %s",
            ex.getField(), ex.getRawValue(), ex.getExpected());
        ApiResponse<Object> response = ApiResponse.errorWithContext(ResponseCode.BadParam, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle custom validation exceptions.
     * Returns HTTP 422 Unprocessable Entity.
     */
    @ExceptionHandler(ValidationException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(ValidationException ex) {
        Map<String, String> errors = new HashMap<>();
        errors.put("error", ex.getMessage());

        log.warn("Business validation failed: {}", ex.getMessage());
        ApiResponse<Map<String, String>> response = ApiResponse.errorWithContext(ResponseCode.BadParam, errors);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
    }

    /**
     * Handle optimistic lock conflicts.
     * Returns HTTP 409 Conflict.
     */
    @ExceptionHandler(ConflictException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleConflictException(ConflictException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());

        ApiResponse<Object> response = ApiResponse.errorWithContext(ResponseCode.BUSINESS_ERROR, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleBusinessException(BusinessException ex) {
        log.error("Business exception:", ex);

        Object detail = isDevEnvironment() ? buildDevDetail(ex) : ex.getMessage();
        ApiResponse<Object> response = ApiResponse.errorWithContext(ex.getResponseCode(), detail);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
    }

    /**
     * Handle Spring Security AccessDeniedException.
     * This is thrown by PermissionInterceptor when the user lacks a required permission.
     * Returns HTTP 403 Forbidden.
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleAccessDeniedException(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());

        ApiResponse<Object> response = ApiResponse.errorWithContext(ResponseCode.FORBIDDEN, ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * Handle framework unchecked exceptions with their declared response codes.
     * This prevents expected business/validation/auth failures from being flattened into HTTP 500.
     */
    @ExceptionHandler(RootUnCheckedException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleRootUncheckedException(RootUnCheckedException ex) {
        ResponseCode responseCode = ex.getResponseCode() != null ? ex.getResponseCode() : ResponseCode.SystemError;
        HttpStatus status = mapResponseCodeToStatus(responseCode);

        if (status.is5xxServerError()) {
            log.error("Framework exception mapped to server error: code={}, message={}", responseCode.getCode(), responseCode.getDesc(), ex);
        } else {
            log.warn("Framework exception mapped to client error: code={}, message={}", responseCode.getCode(), responseCode.getDesc());
        }

        Object detail = ex.getContext() != null
                ? ex.getContext()
                : (isDevEnvironment() ? buildDevDetail(ex) : responseCode.getDesc());

        String message = responseCode.getDesc();
        if (isDevEnvironment()) {
            String exceptionMessage = ex.getMessage();
            if (exceptionMessage != null && !exceptionMessage.isBlank()) {
                message = exceptionMessage;
            }
        }

        ApiResponse<Object> response = ApiResponse.error(responseCode, message, detail);
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Handle all other uncaught exceptions.
     * Returns HTTP 500 Internal Server Error.
     */
    @ExceptionHandler(Exception.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleGenericException(Exception ex) {
        log.error("Unexpected system exception", ex);

        if (isDevEnvironment()) {
            // Dev: expose exception details for faster debugging
            Map<String, String> devDetail = buildDevDetail(ex);
            ApiResponse<Object> response = ApiResponse.errorWithContext(ResponseCode.SystemError, devDetail);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }

        // Production: generic message, no internal details
        String message = "An unexpected error occurred. Please try again later.";
        ApiResponse<Object> response = ApiResponse.errorWithContext(ResponseCode.SystemError, message);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private HttpStatus mapResponseCodeToStatus(ResponseCode responseCode) {
        return switch (responseCode) {
            case WrongEmailFormat, CommonValidationFailed, BadParam -> HttpStatus.BAD_REQUEST;
            case IdentifierAlreadyBeenTaken -> HttpStatus.CONFLICT;
            case InvalidUserNameOrPassword, AccountLocked, SecurityVersionMismatch,
                 PasswordExpired, PasswordTooWeak, PasswordReused, MustChangePassword,
                 UserNotLoginInOrAccessTokenInvalid, MissingAuthorizationHeader,
                 ExpiredAuthorizationHeader, Unauthorized -> HttpStatus.UNAUTHORIZED;
            case PermissionDenied, FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BUSINESS_ERROR, PageDefinitionCantBeEmpty -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PluginConflictDetected, PluginImportFailed -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PluginNotFound -> HttpStatus.NOT_FOUND;
            case SystemError, UnreachableCodePathException, UnsupportedFeature, OK -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
