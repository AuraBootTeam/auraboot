package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.application.security.ExternalApiKeyAuthenticator.ExternalApiKeyPrincipal;
import com.auraboot.framework.openplatform.service.ExternalEventIngressService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/open/v1/event-sources")
@RequiredArgsConstructor
public class ExternalEventIngressController {
    private final ExternalEventIngressService ingressService;

    @PostMapping("/{sourceCode}/events")
    public ResponseEntity<ExternalEventIngressService.IngressResult> accept(
            @AuthenticationPrincipal ExternalApiKeyPrincipal principal,
            @PathVariable String sourceCode,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody String rawBody) {
        return ResponseEntity.accepted().body(
                ingressService.accept(principal, sourceCode, idempotencyKey, rawBody));
    }

    @ExceptionHandler(ExternalEventIngressService.InvalidExternalEventException.class)
    public ResponseEntity<Map<String, Object>> invalidEvent(
            ExternalEventIngressService.InvalidExternalEventException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", exception.getMessage()));
    }

    @ExceptionHandler(ExternalEventIngressService.IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "idempotency_conflict"));
    }
}
