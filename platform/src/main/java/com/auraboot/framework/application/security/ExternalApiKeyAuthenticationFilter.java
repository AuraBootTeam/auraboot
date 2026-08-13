package com.auraboot.framework.application.security;

import com.auraboot.framework.application.tenant.MetaContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Establishes an authenticated tenant context for module-declared external API paths. */
@Component
public class ExternalApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_ATTRIBUTE =
            ExternalApiKeyAuthenticationFilter.class.getName() + ".authenticated";

    private final List<ExternalApiKeyAuthenticator> authenticators;
    private final ObjectMapper objectMapper;

    public ExternalApiKeyAuthenticationFilter(List<ExternalApiKeyAuthenticator> authenticators,
                                              ObjectMapper objectMapper) {
        this.authenticators = authenticators;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ExternalApiKeyAuthenticator authenticator = authenticators.stream()
                .filter(candidate -> candidate.supports(request))
                .findFirst()
                .orElse(null);
        if (authenticator == null) {
            filterChain.doFilter(request, response);
            return;
        }

        long startedAt = System.nanoTime();
        ExternalApiKeyAuthenticator.ExternalApiKeyPrincipal principal;
        try {
            principal = authenticator.authenticate(request);
        } catch (ExternalApiKeyException exception) {
            writeError(response, exception.status(), exception.code());
            return;
        } catch (RuntimeException exception) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "api_key_invalid");
            return;
        }

        if (principal == null || principal.tenantId() == null || principal.keyPid() == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "api_key_invalid");
            return;
        }

        var authorities = principal.scopes().stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList();
        var authentication = new UsernamePasswordAuthenticationToken(
                "api-key:" + principal.keyPid(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        MetaContext.setContext(principal.tenantId(), null, principal.keyPid(), principal.keyName());
        request.setAttribute(AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
        MDC.put("tenantId", String.valueOf(principal.tenantId()));

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            try {
                authenticator.recordCall(principal, request, response.getStatus(), durationMillis);
            } finally {
                MetaContext.clear();
                MDC.remove("tenantId");
                SecurityContextHolder.clearContext();
            }
        }
    }

    private void writeError(HttpServletResponse response, int status, String code) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", code);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }
}
