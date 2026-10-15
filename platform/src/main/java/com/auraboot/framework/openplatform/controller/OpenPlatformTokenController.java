package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.TokenResponse;
import com.auraboot.framework.openplatform.service.OpenPlatformTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
@AuthenticatedAccess("standard OAuth2 /oauth2/token endpoint; the client is authenticated in-controller from HTTP Basic credentials or form client_id/client_secret before any token is issued")
public class OpenPlatformTokenController {
    private final OpenPlatformTokenService tokenService;
    private final com.auraboot.framework.openplatform.service.OpenPlatformOAuthFailureAudit failureAudit;

    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public TokenResponse token(@RequestParam(value = "grant_type", required = false) String grantType,
                               @RequestParam(value = "client_id", required = false) String clientId,
                               @RequestParam(value = "client_secret", required = false) String clientSecret,
                               @RequestParam(value = "scope", required = false) String scope,
                               @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (authorization != null) {
            if (clientId != null || clientSecret != null
                    || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
                throw new OpenPlatformTokenService.InvalidClientException();
            }
            try {
                String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                        StandardCharsets.UTF_8);
                int separator = decoded.indexOf(':');
                if (separator <= 0 || separator == decoded.length() - 1) {
                    throw new OpenPlatformTokenService.InvalidClientException();
                }
                clientId = URLDecoder.decode(decoded.substring(0, separator), StandardCharsets.UTF_8);
                clientSecret = URLDecoder.decode(decoded.substring(separator + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalidEncoding) {
                throw new OpenPlatformTokenService.InvalidClientException();
            }
        }
        return tokenService.issue(grantType, clientId, clientSecret, scope);
    }

    @ExceptionHandler(OpenPlatformTokenService.InvalidClientException.class)
    public ResponseEntity<Map<String, Object>> invalidClient(OpenPlatformTokenService.InvalidClientException error,
            jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        return oauthError(HttpStatus.UNAUTHORIZED, "invalid_client", error.auditIdentity(), request, response);
    }

    @ExceptionHandler(OpenPlatformTokenService.InvalidScopeException.class)
    public ResponseEntity<Map<String, Object>> invalidScope(OpenPlatformTokenService.InvalidScopeException error,
            jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        return oauthError(HttpStatus.BAD_REQUEST, "invalid_scope", error.auditIdentity(), request, response);
    }

    private ResponseEntity<Map<String, Object>> oauthError(HttpStatus status, String error,
            OpenPlatformTokenService.AuditIdentity identity, jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        // Caller-supplied IDs can contain credentials. Correlate this denial with a server-generated ID.
        String requestId = java.util.UUID.randomUUID().toString();
        request.setAttribute(com.auraboot.framework.openplatform.security.OpenApiRequestIdFilter.REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);
        failureAudit.record(request, requestId, status.value(), error, identity);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return ResponseEntity.status(status).body(body);
    }
}
