package com.auraboot.framework.openplatform.security;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator;
import com.auraboot.framework.application.security.ExternalMachineAuthException;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.openplatform.entity.OpenApiCallAudit;
import com.auraboot.framework.openplatform.mapper.OpenApiCallAuditMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiRateLimitMapper;
import com.auraboot.framework.openplatform.mapper.OpenPlatformAuthMapper;
import com.auraboot.framework.openplatform.service.OpenApiCapabilityRegistry;
import com.auraboot.framework.openplatform.service.OpenPlatformSecretCodec;
import com.auraboot.framework.openplatform.service.OpenPlatformTokenService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class OpenPlatformBearerAuthenticator implements ExternalMachineAuthenticator {
    private static final String PREFIX = "/api/open/v1/";
    private static final String BEARER = "Bearer ";

    private final OpenApiCallAuditMapper auditMapper;
    private final OpenApiRateLimitMapper rateLimitMapper;
    private final OpenApiCapabilityRegistry capabilityRegistry;
    private final OpenPlatformSecretCodec secretCodec;
    private final ObjectMapper objectMapper;
    private final OpenPlatformAuthMapper authMapper;

    @Override
    public boolean supports(HttpServletRequest request) {
        return request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    @Transactional
    public MachinePrincipal authenticate(HttpServletRequest request) {
        OpenApiCapabilityRegistry.Capability capability = capabilityRegistry.resolve(request.getMethod(), request.getRequestURI()).orElse(null);
        if (capability == null && !request.getRequestURI().matches("/api/open/v1/(?:resources/[a-z][a-z0-9.-]{1,99}(?:/[^/]+)?|queries/[a-z][a-z0-9.-]{1,93}:execute)")) {
            throw new ExternalMachineAuthException(404, "open_api_capability_not_found");
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER)
                || authorization.length() <= BEARER.length()) {
            throw new ExternalMachineAuthException(401, "invalid_token");
        }
        String rawToken = authorization.substring(BEARER.length()).trim();
        Instant usedAt = Instant.now();
        String tokenHash = secretCodec.sha256(rawToken);
        if (capability == null) {
            // Dynamic route authority is selected only from the authenticated token's tenant.
            var identity = authMapper.findToken(tokenHash, OpenPlatformTokenService.AUDIENCE, usedAt);
            if (identity == null || !"active".equals(identity.installationStatus()) || !"active".equals(identity.applicationStatus())) {
                throw new ExternalMachineAuthException(401, "invalid_token");
            }
            capability = capabilityRegistry.resolve(identity.tenantId(), request.getMethod(), request.getRequestURI())
                    .orElseThrow(() -> new ExternalMachineAuthException(404, "open_api_capability_not_found"));
        }
        var token = rateLimitMapper.authenticate(tokenHash,
                OpenPlatformTokenService.AUDIENCE, usedAt,
                usedAt.truncatedTo(ChronoUnit.MINUTES), capability.requiredScope());
        if (token == null || !"active".equals(token.installationStatus())
                || !"active".equals(token.applicationStatus())) {
            throw new ExternalMachineAuthException(401, "invalid_token");
        }
        Set<String> scopes = readScopes(token.scopes());
        if (!scopes.contains(capability.requiredScope())) {
            throw new ExternalMachineAuthException(403, "insufficient_scope");
        }
        if (token.consumedCount() == null) {
            throw new ExternalMachineAuthException(429, "rate_limit_exceeded");
        }
        OpenApiBusinessAudit.capture(request, capability);
        return new MachinePrincipal(token.tenantId(), token.tokenPid(), token.applicationPid(), scopes,
                token.applicationPid(), token.installationPid(), token.environment(), token.tokenPid());
    }

    @Override
    public void recordCall(MachinePrincipal principal, HttpServletRequest request,
                           int status, long durationMillis) {
        OpenApiCallAudit audit = new OpenApiCallAudit();
        audit.setPid(UniqueIdGenerator.generate());
        audit.setTenantId(principal.tenantId());
        audit.setApplicationPid(principal.applicationPid());
        audit.setInstallationPid(principal.installationPid());
        audit.setTokenPid(principal.tokenPid());
        audit.setRequestId((String) request.getAttribute(OpenApiRequestIdFilter.REQUEST_ID_ATTRIBUTE));
        audit.setHttpMethod(request.getMethod());
        audit.setRequestPath(request.getRequestURI());
        audit.setResponseStatus(status);
        audit.setDurationMs(durationMillis);
        audit.setRemoteAddress(request.getRemoteAddr());
        audit.setOccurredAt(Instant.now());
        audit.setBusinessDetails(OpenApiBusinessAudit.details(request));
        auditMapper.insert(audit);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordAuthenticationFailure(HttpServletRequest request, int status,
                                            String code, long durationMillis) {
        String authorization = request.getHeader("Authorization");
        String raw = authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()).trim() : null;
        var identity = raw == null || raw.isEmpty() ? null
                : authMapper.findTokenIdentityForAudit(secretCodec.sha256(raw), OpenPlatformTokenService.AUDIENCE);
        OpenApiCallAudit audit = new OpenApiCallAudit();
        audit.setPid(UniqueIdGenerator.generate());
        if (identity != null) {
            audit.setTenantId(identity.tenantId());
            audit.setApplicationPid(identity.applicationPid());
            audit.setInstallationPid(identity.installationPid());
            audit.setTokenPid(identity.tokenPid());
        }
        String requestId = (String) request.getAttribute(OpenApiRequestIdFilter.REQUEST_ID_ATTRIBUTE);
        audit.setRequestId(requestId != null && (raw == null || raw.isEmpty() || !requestId.contains(raw))
                ? requestId : java.util.UUID.randomUUID().toString());
        audit.setHttpMethod(Set.of("GET", "POST", "PATCH", "PUT", "DELETE", "HEAD", "OPTIONS")
                .contains(request.getMethod()) ? request.getMethod() : "OTHER");
        audit.setRequestPath(authenticationAuditPath(request));
        audit.setResponseStatus(status);
        audit.setDurationMs(durationMillis);
        String remote = request.getRemoteAddr();
        audit.setRemoteAddress(remote != null && remote.matches("[0-9a-fA-F:.]{1,96}") ? remote : null);
        audit.setOccurredAt(Instant.now());
        audit.setBusinessDetails(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("actorKind", "unauthenticated").put("phase", "authentication").put("code", code));
        if (auditMapper.insertAuthenticationFailure(audit) != 1) {
            throw new IllegalStateException("Authentication failure audit was not persisted");
        }
    }

    private String authenticationAuditPath(HttpServletRequest request) {
        var published = capabilityRegistry.resolve(request.getMethod(), request.getRequestURI());
        if (published.isPresent()) return published.get().pathPattern();
        if (request.getRequestURI().matches("/api/open/v1/resources/[^/]+/[^/]+"))
            return "/api/open/v1/resources/{resource}/{pid}";
        if (request.getRequestURI().matches("/api/open/v1/resources/[^/]+"))
            return "/api/open/v1/resources/{resource}";
        if (request.getRequestURI().matches("/api/open/v1/queries/[^/]+:execute"))
            return "/api/open/v1/queries/{query}:execute";
        return "/api/open/v1/{unpublished}";
    }

    private Set<String> readScopes(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new ExternalMachineAuthException(401, "invalid_token");
        }
    }
}
