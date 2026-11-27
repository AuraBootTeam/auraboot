package com.auraboot.framework.openplatform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@lombok.RequiredArgsConstructor
public class OpenApiRequestIdFilter extends OncePerRequestFilter {
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.auraboot.framework.openplatform.mapper.OpenApiCallAuditMapper auditMapper;
    public static final String REQUEST_ID_ATTRIBUTE = OpenApiRequestIdFilter.class.getName() + ".requestId";
    public static final String STARTED_NANOS_ATTRIBUTE = OpenApiRequestIdFilter.class.getName() + ".startedNanos";
    private static final int MAX_REQUEST_ID_LENGTH = 128;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        request.setAttribute(STARTED_NANOS_ATTRIBUTE, System.nanoTime());
        String supplied = request.getHeader("X-Request-Id");
        String authorization = request.getHeader("Authorization");
        String requestId = supplied == null || !supplied.matches("[A-Za-z0-9_.:-]{1,128}") || supplied.length() > MAX_REQUEST_ID_LENGTH
                || (authorization != null && authorization.contains(supplied))
                ? UUID.randomUUID().toString() : supplied;
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);
        MDC.put("requestId", requestId);
        boolean capture = request.getRequestURI().startsWith("/api/open/v1/");
        OpenApiHttpCapture.Request capturedRequest = capture ? new OpenApiHttpCapture.Request(request) : null;
        OpenApiHttpCapture.Response capturedResponse = capture ? new OpenApiHttpCapture.Response(response) : null;
        boolean completed = false;
        try {
            filterChain.doFilter(capture ? capturedRequest : request, capture ? capturedResponse : response);
            completed = true;
        } finally {
            try {
                boolean captureComplete = capture && completed && !capturedRequest.isAsyncStarted();
                if (captureComplete) {
                    capturedResponse.finishCapture();
                }
                if (capture && request.getAttribute(OpenApiHttpCapture.TARGET) instanceof OpenApiHttpCapture.Target target
                        && target.tenantId() != null && target.installationPid() != null) {
                    var details = new OpenApiHttpCapture(objectMapper).snapshot(capturedRequest, capturedResponse, captureComplete);
                    if (auditMapper.updateHttpDetails(target.pid(), target.tenantId(), target.installationPid(), details) != 1)
                        throw new IllegalStateException("HTTP audit capture target was not found");
                }
            } finally { MDC.remove("requestId"); }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/open/v1/")
                && !request.getRequestURI().equals("/oauth2/token");
    }
}
