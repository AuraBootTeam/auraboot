package com.auraboot.framework.openplatform.security;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator;
import com.auraboot.framework.application.security.ExternalMachineAuthException;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.openplatform.entity.OpenApiCallAudit;
import com.auraboot.framework.openplatform.mapper.OpenApiCallAuditMapper;
import com.auraboot.framework.openplatform.mapper.OpenPlatformAuthMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiRateLimitMapper;
import com.auraboot.framework.openplatform.service.OpenApiCapabilityRegistry;
import com.auraboot.framework.openplatform.service.OpenPlatformSecretCodec;
import com.auraboot.framework.openplatform.service.OpenPlatformTokenService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class OpenPlatformBearerAuthenticator implements ExternalMachineAuthenticator {
    private static final String PREFIX = "/api/open/v1/";
    private static final String BEARER = "Bearer ";

    private final OpenPlatformAuthMapper authMapper;
    private final OpenApiCallAuditMapper auditMapper;
    private final OpenApiRateLimitMapper rateLimitMapper;
    private final OpenApiCapabilityRegistry capabilityRegistry;
    private final OpenPlatformSecretCodec secretCodec;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(HttpServletRequest request) {
        return request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    public MachinePrincipal authenticate(HttpServletRequest request) {
        OpenApiCapabilityRegistry.Capability capability = capabilityRegistry
                .resolve(request.getMethod(), request.getRequestURI())
                .orElseThrow(() -> new ExternalMachineAuthException(404, "open_api_capability_not_found"));
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER)
                || authorization.length() <= BEARER.length()) {
            throw new ExternalMachineAuthException(401, "invalid_token");
        }
        String rawToken = authorization.substring(BEARER.length()).trim();
        OpenPlatformAuthMapper.TokenAuthRecord token = authMapper.findToken(
                secretCodec.sha256(rawToken), OpenPlatformTokenService.AUDIENCE, Instant.now());
        if (token == null || !"active".equals(token.installationStatus())
                || !"active".equals(token.applicationStatus())) {
            throw new ExternalMachineAuthException(401, "invalid_token");
        }
        Set<String> scopes = readScopes(token.scopes());
        if (!scopes.contains(capability.requiredScope())) {
            throw new ExternalMachineAuthException(403, "insufficient_scope");
        }
        if (rateLimitMapper.consume(token.installationId(), Instant.now().truncatedTo(ChronoUnit.MINUTES),
                token.rateLimitPerMinute()) == null) {
            throw new ExternalMachineAuthException(429, "rate_limit_exceeded");
        }
        authMapper.touchToken(token.tokenPid(), Instant.now());
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
        auditMapper.insert(audit);
    }

    private Set<String> readScopes(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new ExternalMachineAuthException(401, "invalid_token");
        }
    }
}
