package com.auraboot.framework.openplatform.controller;

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
public class OpenPlatformTokenController {
    private final OpenPlatformTokenService tokenService;

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
    public ResponseEntity<Map<String, Object>> invalidClient() {
        return oauthError(HttpStatus.UNAUTHORIZED, "invalid_client");
    }

    @ExceptionHandler(OpenPlatformTokenService.InvalidScopeException.class)
    public ResponseEntity<Map<String, Object>> invalidScope() {
        return oauthError(HttpStatus.BAD_REQUEST, "invalid_scope");
    }

    private ResponseEntity<Map<String, Object>> oauthError(HttpStatus status, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return ResponseEntity.status(status).body(body);
    }
}
