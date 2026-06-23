package com.auraboot.framework.application.web.handler;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.exception.PermissionDeniedException;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.bpm.converter.BpmnConversionException;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.i18n.util.I18nLocaleResolver;
import com.auraboot.framework.meta.exception.TemporalParseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    @Autowired
    private I18nService i18nService;
    @Autowired
    private I18nLocaleResolver i18nLocaleResolver;

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

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncRequestTimeout(AsyncRequestTimeoutException ex) {
        // SSE/long-polling timeouts are expected idle-connection lifecycle events.
        // Do not write an ApiResponse because the response may already be text/event-stream.
        log.debug("Async request timed out: {}", ex.getMessage());
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

    /**
     * BPMN designer-to-XML conversion or validation failures (naked sequence flows on
     * exclusive gateways, multiple default flows, etc.). Returns 400 with the specific
     * cause so the designer UI can surface it instead of a generic "Internal system error".
     */
    @ExceptionHandler(BpmnConversionException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleBpmnConversionException(BpmnConversionException ex) {
        log.warn("BPMN conversion failed: {}", ex.getMessage());
        ApiResponse<Object> response = ApiResponse.errorWithContext(ResponseCode.BadParam, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Honor a Spring {@link ResponseStatusException}'s own status instead of letting it fall through
     * to the catch-all 500. Without this, any code raising {@code ResponseStatusException} (the
     * public keyed-collect guard's 403/429/400, the authenticated collect's 401) was silently
     * mapped to 500 because the {@code @ExceptionHandler(Exception.class)} catch-all matched first.
     * The reason phrase is a stable token (e.g. {@code site_key_invalid}) returned as the message.
     */
    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String reason = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        log.warn("ResponseStatusException {}: {}", status, reason);
        ApiResponse<Object> response = ApiResponse.errorWithContext(ResponseCode.BUSINESS_ERROR, reason);
        return ResponseEntity.status(status).body(response);
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleBusinessException(BusinessException ex,
                                                                       HttpServletRequest request) {
        log.error("Business exception:", ex);

        ResponseCode responseCode = ex.getResponseCode() != null
                ? ex.getResponseCode()
                : ResponseCode.BUSINESS_ERROR;
        Object detail = isDevEnvironment() ? buildDevDetail(ex) : localizeBusinessMessage(ex, request);
        ApiResponse<Object> response = ApiResponse.errorWithContext(responseCode, detail);
        return ResponseEntity.status(mapResponseCodeToStatus(responseCode)).body(response);
    }

    /**
     * Localize a BusinessException's user-facing message. Static {@code $i18n:<key>} messages go
     * through {@link #localizeI18nMessage}; parameterized ones (constructed via
     * {@code BusinessException.i18n(key, args)}) substitute {@code {0}} via
     * {@link I18nService#getMessage}. Non-{@code $i18n:} messages pass through unchanged.
     */
    String localizeBusinessMessage(BusinessException ex, HttpServletRequest request) {
        Object[] args = ex.getI18nArgs();
        String text = ex.getMessage();
        if (args == null || args.length == 0) {
            return localizeI18nMessage(text, request);
        }
        if (text == null || !text.startsWith("$i18n:")) {
            return text;
        }
        String key = text.substring("$i18n:".length());
        String locale = i18nLocaleResolver.resolveLocale(request);
        String value = i18nService.getMessage(locale, key, args);
        if (value == null) {
            value = i18nService.getMessage("zh-CN", key, args); // base-locale fallback (ja/ko gaps)
        }
        return value != null ? value : key;
    }

    /**
     * Resolve a {@code $i18n:<key>} message to the request locale via the existing i18n catalog
     * (I18nLocaleResolver + I18nService), mirroring TenantSelectionController (#885). Messages
     * NOT prefixed with {@code $i18n:} pass through unchanged, so this is a no-op for every
     * BusinessException message not yet migrated to a key — zero behavior change for those.
     */
    String localizeI18nMessage(String text, HttpServletRequest request) {
        if (text == null || !text.startsWith("$i18n:")) {
            return text;
        }
        String key = text.substring("$i18n:".length());
        String locale = i18nLocaleResolver.resolveLocale(request);
        String value = i18nService.getValue(locale, key);
        if (value == null) {
            value = i18nService.getValue("zh-CN", key); // base-locale fallback (ja/ko gaps)
        }
        return value != null ? value : key;
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
     * Handle malformed request bodies — JSON parse failures, type mismatches
     * (e.g. nested object posted into a {@code Map<String,String>} field).
     *
     * <p>Without this handler such errors fall through to the generic
     * {@link Exception} handler and surface as HTTP 500 "An unexpected error
     * occurred", which hides client-side contract drift behind a server error.
     * Returns HTTP 400 with the parse error message and (in dev profiles)
     * the underlying root cause class+message for faster diagnosis.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());

        // Prefer the most specific message available. Spring wraps Jackson
        // errors so the root cause carries the useful "Cannot deserialize
        // value of type X from Object value" detail.
        Throwable rootCause = ex.getMostSpecificCause();
        String message = rootCause != null && rootCause.getMessage() != null
                ? rootCause.getMessage()
                : (ex.getMessage() != null ? ex.getMessage() : "Malformed request body");

        Object detail;
        if (isDevEnvironment()) {
            Map<String, String> devDetail = new HashMap<>();
            devDetail.put("exception", ex.getClass().getSimpleName());
            devDetail.put("detail", message);
            if (rootCause != null && rootCause != ex) {
                devDetail.put("cause", rootCause.getClass().getSimpleName()
                        + ": " + rootCause.getMessage());
            }
            detail = devDetail;
        } else {
            detail = message;
        }

        ApiResponse<Object> response = ApiResponse.errorWithContext(ResponseCode.BadParam, detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle requests using an unsupported HTTP method.
     * Returns HTTP 405 instead of falling through to a generic 500.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        log.warn("HTTP method not supported: method={}, supported={}",
                ex.getMethod(), ex.getSupportedHttpMethods());
        ApiResponse<Object> response = ApiResponse.errorWithContext(ResponseCode.BadParam, ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
    }

    /**
     * Handle Accept headers that cannot consume the controller's declared
     * response type, e.g. requesting JSON from an SSE-only endpoint.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException ex) {
        log.warn("HTTP media type not acceptable: {}", ex.getMessage());
        ApiResponse<Object> response = ApiResponse.errorWithContext(ResponseCode.BadParam, ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(response);
    }

    /**
     * Handle non-multipart or malformed multipart requests.
     * Returns HTTP 400 because the route exists but the request shape is invalid.
     */
    @ExceptionHandler(MultipartException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleMultipartException(MultipartException ex) {
        log.warn("Multipart request error: {}", ex.getMessage());
        ApiResponse<Object> response = ApiResponse.errorWithContext(ResponseCode.BadParam, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle missing static resources separately from unexpected server errors.
     * Spring raises this for unknown API paths after controller mapping fails.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseBody
    public ResponseEntity<ApiResponse<Object>> handleNoResourceFoundException(NoResourceFoundException ex) {
        log.warn("No resource found: {}", ex.getMessage());
        ApiResponse<Object> response = ApiResponse.errorWithContext(ResponseCode.NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
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
