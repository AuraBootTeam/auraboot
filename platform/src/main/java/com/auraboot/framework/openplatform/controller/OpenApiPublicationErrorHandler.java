package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.openplatform.service.OpenApiPublicationService.PublicationConflictException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Human administration uses the host envelope and explicit recoverable HTTP statuses. */
@Order(0)
@RestControllerAdvice(assignableTypes = {OpenApiPublicationController.class, OpenApiQueryPublicationController.class, OpenApiActionPublicationController.class})
public class OpenApiPublicationErrorHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error(400, error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformed() {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error(400, "Publication request contains invalid values or types"));
    }

    @ExceptionHandler(PublicationConflictException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(PublicationConflictException error) {
        return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error(409, error.getReason()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> denied() {
        return ResponseEntity.status(403).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error(403, "Open Platform administrator permission required"));
    }
}
