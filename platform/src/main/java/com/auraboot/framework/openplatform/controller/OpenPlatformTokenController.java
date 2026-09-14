package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.TokenResponse;
import com.auraboot.framework.openplatform.service.OpenPlatformTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class OpenPlatformTokenController {
    private final OpenPlatformTokenService tokenService;

    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public TokenResponse token(@RequestParam(value = "grant_type", required = false) String grantType,
                               @RequestParam(value = "client_id", required = false) String clientId,
                               @RequestParam(value = "client_secret", required = false) String clientSecret,
                               @RequestParam(value = "scope", required = false) String scope) {
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
