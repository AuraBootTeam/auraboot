package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.openplatform.service.ExternalEventIngressService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
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
@AuthenticatedAccess
@RequiredArgsConstructor
public class ExternalEventIngressController {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.tracing.Tracer tracer;

    private Map<String, Object> error(String code, String message, java.util.List<?> errors) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("code", code); body.put("message", message);
        String requestId = org.slf4j.MDC.get("requestId");
        if (requestId != null) body.put("requestId", requestId);
        var span = tracer == null ? null : tracer.currentSpan();
        if (span != null) body.put("traceId", span.context().traceId());
        body.put("errors", errors);
        LOG.warn("External ingress rejected code={} requestId={} fields={}", code, requestId, errors);
        return body;
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExternalEventIngressController.class);

    private final ExternalEventIngressService ingressService;

    private final com.auraboot.framework.openplatform.service.InstalledIntegrationService installedIntegrations;

    @org.springframework.web.bind.annotation.GetMapping("/{sourceCode}/events/{externalEventId}")
    public Map<String,Object> result(@AuthenticationPrincipal MachinePrincipal principal,
            @PathVariable String sourceCode, @PathVariable String externalEventId) {
        com.auraboot.framework.openplatform.security.OpenApiBusinessAudit.attempt(sourceCode,"result",null);
        var result=installedIntegrations.result(principal,sourceCode,externalEventId);
        com.auraboot.framework.openplatform.security.OpenApiBusinessAudit.attempt(sourceCode,"result",
                result.get("recordPid") instanceof String pid ? pid : null);
        return result;
    }

    @PostMapping("/{sourceCode}/events")
    public ResponseEntity<Map<String,Object>> accept(
            @AuthenticationPrincipal MachinePrincipal principal,
            @PathVariable String sourceCode,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody String rawBody) {
        com.auraboot.framework.openplatform.security.OpenApiBusinessAudit.attempt(sourceCode,"accept",null);
        var accepted=ingressService.accept(principal, sourceCode, idempotencyKey, rawBody);
        com.auraboot.framework.openplatform.security.OpenApiBusinessAudit.result(sourceCode,"accept",
                accepted.recordPid(),accepted.duplicate());
        return ResponseEntity.accepted().body(accepted.publicReceipt());
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String,Object>> bindingDenied(SecurityException denial) {
        // Fail closed toward the client (no internal diagnostics in the body) but never
        // silently: the denial is logged with its stack, whose frames pinpoint the exact
        // failing check inside resolve()/verify — matching MetaApiExceptionHandler's
        // convention for the platform-wide SecurityException handling.
        LOG.warn("Installed integration binding denied: {}", denial.getMessage(), denial);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("installation_binding_denied", "The authenticated installation is not authorized for this integration. Check its scope grants and integration binding.", java.util.List.of()));
    }

    @ExceptionHandler(ExternalEventIngressService.InvalidExternalEventException.class)
    public ResponseEntity<Map<String, Object>> invalidEvent(
            ExternalEventIngressService.InvalidExternalEventException exception) {
        return ResponseEntity.badRequest().body(error(exception.code(), exception.explanation(), exception.errors()));
    }

    @ExceptionHandler(ExternalEventIngressService.IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error("idempotency_conflict", "This stable request identity was already used with different content. Retry the original request unchanged, or use a new identity for a new operation.", java.util.List.of(new com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.FieldError("$", "conflict", "Idempotency-Key, event id and operation identity must consistently refer to the same original request."))));
    }
}
