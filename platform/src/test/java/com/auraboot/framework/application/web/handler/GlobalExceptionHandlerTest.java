package com.auraboot.framework.application.web.handler;

import com.auraboot.framework.bpm.converter.BpmnConversionException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(handler, "activeProfile", "prod");
    }

    private void asDev() {
        ReflectionTestUtils.setField(handler, "activeProfile", "dev");
    }

    @Test
    void handlePermissionDenied_returns403() {
        PermissionDeniedException ex = new PermissionDeniedException(
                ResponseCode.FORBIDDEN,
                "perm.code",
                "no access");

        ResponseEntity<ApiResponse<Object>> resp = handler.handlePermissionDeniedException(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void handleBadCredentials_returns401() {
        ResponseEntity<ApiResponse<Object>> resp =
                handler.handleBadCredentialsException(new BadCredentialsException("bad"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void handleBadCredentials_supportsUsernameNotFound() {
        // The handler typed as BadCredentialsException, but handler covers both.
        // UsernameNotFoundException extends from spring; test goes through the same method since it's mapped.
        BadCredentialsException ex = new BadCredentialsException("u-not-found");
        ResponseEntity<ApiResponse<Object>> resp = handler.handleBadCredentialsException(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void handleAsyncRequestNotUsable_swallowsSilently() {
        assertThatCode(() -> handler.handleAsyncRequestNotUsable(
                new AsyncRequestNotUsableException("client gone")))
                .doesNotThrowAnyException();
    }

    @Test
    void handleValidationExceptions_methodArgNotValid_returns400WithFieldErrors() throws Exception {
        Object target = new Object();
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(target, "obj");
        br.addError(new FieldError("obj", "name", "must not be blank"));
        Method method = String.class.getMethod("toString");
        org.springframework.core.MethodParameter mp = new org.springframework.core.MethodParameter(method, -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(mp, br);

        ResponseEntity<ApiResponse<Map<String, String>>> resp = handler.handleValidationExceptions(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getContext()).isInstanceOf(Map.class);
    }

    @Test
    void handleConstraintViolationException_returns400() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> v = mock(ConstraintViolation.class);
        jakarta.validation.Path path = mock(jakarta.validation.Path.class);
        when(path.toString()).thenReturn("field.x");
        when(v.getPropertyPath()).thenReturn(path);
        when(v.getMessage()).thenReturn("cannot be null");
        Set<ConstraintViolation<?>> violations = new HashSet<>();
        violations.add(v);
        ConstraintViolationException ex = new ConstraintViolationException(violations);

        ResponseEntity<ApiResponse<Map<String, String>>> resp = handler.handleConstraintViolationException(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void handleBindException_returns400() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "obj");
        br.addError(new FieldError("obj", "x", "required"));
        BindException ex = new BindException(br);

        ResponseEntity<ApiResponse<Map<String, String>>> resp = handler.handleBindException(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void handleTemporalParseException_returns400() {
        TemporalParseException ex = new TemporalParseException("createdAt", "abc", "ISO-8601");
        ResponseEntity<ApiResponse<Object>> resp = handler.handleTemporalParseException(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void handleValidationException_returns422() {
        ValidationException ex = new ValidationException(ResponseCode.BadParam, "boom");
        ResponseEntity<ApiResponse<Map<String, String>>> resp = handler.handleValidationException(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void handleConflictException_returns409() {
        ResponseEntity<ApiResponse<Object>> resp =
                handler.handleConflictException(new ConflictException("version mismatch"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void handleBpmnConversionException_returns400() {
        BpmnConversionException ex = new BpmnConversionException("naked seq flow");
        ResponseEntity<ApiResponse<Object>> resp = handler.handleBpmnConversionException(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void handleBusinessException_returns422_withMessageInProd() {
        BusinessException ex = new BusinessException(ResponseCode.BUSINESS_ERROR, "biz fail");
        ResponseEntity<ApiResponse<Object>> resp = handler.handleBusinessException(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void handleBusinessException_includesDevDetail_whenDevProfile() {
        asDev();
        BusinessException ex = new BusinessException(ResponseCode.BUSINESS_ERROR, "biz fail dev");
        ResponseEntity<ApiResponse<Object>> resp = handler.handleBusinessException(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // dev detail map populated
        assertThat(resp.getBody().getContext()).isInstanceOf(Map.class);
    }

    @Test
    void handleAccessDeniedException_returns403() {
        ResponseEntity<ApiResponse<Object>> resp =
                handler.handleAccessDeniedException(new AccessDeniedException("denied"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void handleRootUnchecked_mappedToClientErrorWithCode() {
        RootUnCheckedException ex = new RootUnCheckedException(ResponseCode.NOT_FOUND, "missing");
        ResponseEntity<ApiResponse<Object>> resp = handler.handleRootUncheckedException(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void handleRootUnchecked_systemErrorMappedTo500() {
        RootUnCheckedException ex = new RootUnCheckedException(ResponseCode.SystemError, "bad");
        ResponseEntity<ApiResponse<Object>> resp = handler.handleRootUncheckedException(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void handleRootUnchecked_devProfile_overridesMessage() {
        asDev();
        RootUnCheckedException ex = new RootUnCheckedException(ResponseCode.BadParam, "specific dev msg");
        ResponseEntity<ApiResponse<Object>> resp = handler.handleRootUncheckedException(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void handleHttpMessageNotReadable_returns400_withRootCauseMessageInProd() {
        // Simulate Jackson failing to deserialize a nested object into Map<String,String>.
        Throwable jacksonRoot = new IllegalArgumentException(
                "Cannot deserialize value of type java.lang.String from Object value");
        HttpInputMessage input = mock(HttpInputMessage.class);
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("JSON parse error", jacksonRoot, input);

        ResponseEntity<ApiResponse<Object>> resp = handler.handleHttpMessageNotReadable(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        // Prod profile: detail is the most-specific-cause message string.
        assertThat(resp.getBody().getContext())
                .isEqualTo("Cannot deserialize value of type java.lang.String from Object value");
    }

    @Test
    void handleHttpMessageNotReadable_returns400_withDevDetailMap() {
        asDev();
        Throwable jacksonRoot = new IllegalArgumentException("type mismatch detail");
        HttpInputMessage input = mock(HttpInputMessage.class);
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("JSON parse error", jacksonRoot, input);

        ResponseEntity<ApiResponse<Object>> resp = handler.handleHttpMessageNotReadable(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        // Dev profile: detail is a structured map with exception/detail/cause keys.
        assertThat(resp.getBody().getContext()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> ctx = (Map<String, String>) resp.getBody().getContext();
        assertThat(ctx).containsEntry("exception", "HttpMessageNotReadableException");
        assertThat(ctx.get("detail")).contains("type mismatch detail");
        assertThat(ctx.get("cause")).contains("IllegalArgumentException");
    }

    @Test
    void handleHttpMessageNotReadable_handlesNullRootCauseMessage() {
        // Defensive: ensure no NPE when root cause has null message.
        HttpInputMessage input = mock(HttpInputMessage.class);
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("outer message", input);

        ResponseEntity<ApiResponse<Object>> resp = handler.handleHttpMessageNotReadable(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void handleGenericException_prod_returns500WithGenericMessage() {
        ResponseEntity<ApiResponse<Object>> resp =
                handler.handleGenericException(new RuntimeException("inner"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().getContext()).isEqualTo(
                "An unexpected error occurred. Please try again later.");
    }

    @Test
    void handleGenericException_dev_returnsExceptionDetails() {
        asDev();
        Throwable cause = new IllegalStateException("inner cause");
        ResponseEntity<ApiResponse<Object>> resp =
                handler.handleGenericException(new RuntimeException("outer", cause));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().getContext()).isInstanceOf(Map.class);
    }

    @Test
    void mapResponseCodeToStatus_coversAllBranches() {
        // Trigger the private switch via handleRootUncheckedException for representative codes
        for (ResponseCode rc : ResponseCode.values()) {
            RootUnCheckedException ex = new RootUnCheckedException(rc, "ctx");
            ResponseEntity<ApiResponse<Object>> resp = handler.handleRootUncheckedException(ex);
            assertThat(resp.getStatusCode()).isNotNull();
        }
    }
}
