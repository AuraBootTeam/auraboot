package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Order(0)
@RestControllerAdvice(assignableTypes = IntegrationTokenController.class)
public class IntegrationTokenErrorHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, error.getMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiResponse<Void>> malformed() {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "Invalid integration token request"));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> denied() {
        return ResponseEntity.status(403).body(ApiResponse.error(403, "Application runtime-management permission required"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> status(ResponseStatusException error) {
        int status = error.getStatusCode().value();
        return ResponseEntity.status(status).body(ApiResponse.error(status, error.getReason()));
    }
}
