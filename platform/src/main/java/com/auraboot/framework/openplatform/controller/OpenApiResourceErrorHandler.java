package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.meta.exception.MetaRecordNotFoundException;
import com.auraboot.framework.openplatform.service.OpenApiPreconditionException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** Public resource-specific status contracts; common authentication errors retain the host handler. */
@Order(0)
@RestControllerAdvice(assignableTypes = {OpenApiResourceWriteController.class, OpenPlatformFacadeController.class, OpenApiQueryController.class, OpenApiActionController.class})
public class OpenApiResourceErrorHandler {
    // Match the root denial before Spring can select its validation cause as a 400.
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<com.auraboot.framework.common.dto.ApiResponse<Object>> denied() {
        return ResponseEntity.status(403).body(com.auraboot.framework.common.dto.ApiResponse.errorWithContext(
                com.auraboot.framework.common.constant.ResponseCode.FORBIDDEN, "Current resource or query authorization required"));
    }
    @ExceptionHandler(com.auraboot.framework.openplatform.service.OpenApiPublicationService.SourceBindingConflictException.class)
    public ResponseEntity<Map<String, Object>> sourceChanged() {
        return ResponseEntity.status(409).body(Map.of("success", false, "code", "open_api_source_changed",
                "message", "Published resource source binding is unavailable or changed"));
    }
    @ExceptionHandler(com.auraboot.framework.openplatform.service.OpenApiQueryService.QueryRateLimitException.class)
    public ResponseEntity<Map<String, Object>> queryRateLimit() {
        return ResponseEntity.status(429).header("Retry-After", "60").body(Map.of("success", false,
                "code", "open_api_query_rate_limited", "message", "Published query execution rate budget exhausted"));
    }
    @ExceptionHandler(com.auraboot.framework.openplatform.service.OpenApiQueryService.QueryDeadlineExceededException.class)
    public ResponseEntity<Map<String, Object>> queryDeadline() {
        return ResponseEntity.status(504).body(Map.of("success", false, "code", "open_api_query_timeout",
                "message", "Published query execution deadline exceeded"));
    }
    @ExceptionHandler(com.auraboot.framework.openplatform.service.OpenApiQueryService.QueryExecutionUnavailableException.class)
    public ResponseEntity<Map<String, Object>> queryUnavailable() {
        return ResponseEntity.status(503).body(Map.of("success", false, "code", "open_api_query_unavailable",
                "message", "Published query execution is unavailable"));
    }
    @ExceptionHandler(OpenApiPreconditionException.class)
    public ResponseEntity<Map<String, Object>> precondition(OpenApiPreconditionException error) {
        return ResponseEntity.status(412).body(Map.of("success", false, "code", "precondition_failed", "message", error.getMessage()));
    }
    @ExceptionHandler(MetaRecordNotFoundException.class)
    public ResponseEntity<Map<String, Object>> absent(MetaRecordNotFoundException error) {
        return ResponseEntity.status(404).body(Map.of("success", false, "code", "not_found", "message", "Resource record is unavailable"));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "code", "invalid_request", "message", error.getMessage()));
    }
}
