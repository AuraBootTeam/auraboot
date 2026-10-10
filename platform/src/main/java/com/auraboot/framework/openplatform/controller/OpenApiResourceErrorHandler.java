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
@RestControllerAdvice(assignableTypes = {OpenApiResourceWriteController.class, OpenPlatformFacadeController.class, OpenApiQueryController.class})
public class OpenApiResourceErrorHandler {
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
