package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.TokenResponse;
import com.auraboot.framework.openplatform.entity.ApplicationAccessToken;
import com.auraboot.framework.openplatform.mapper.ApplicationAccessTokenMapper;
import com.auraboot.framework.openplatform.mapper.ApplicationScopeGrantMapper;
import com.auraboot.framework.openplatform.mapper.OpenPlatformAuthMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OpenPlatformTokenService {
    public static final String AUDIENCE = "auraboot-open-api";

    private final OpenPlatformAuthMapper authMapper;
    private final ApplicationScopeGrantMapper scopeMapper;
    private final ApplicationAccessTokenMapper tokenMapper;
    private final OpenPlatformSecretCodec secretCodec;
    private final PasswordEncoder passwordEncoder;

    @Value("${open-platform.access-token-ttl:PT1H}")
    private Duration tokenTtl;

    @Transactional
    public TokenResponse issue(String grantType, String clientId, String clientSecret, String requestedScope) {
        if (!"client_credentials".equals(grantType) || clientId == null || clientSecret == null) {
            throw new InvalidClientException();
        }
        Instant now = Instant.now();
        OpenPlatformAuthMapper.CredentialAuthRecord credential = authMapper.findCredential(clientId);
        if (credential == null || !passwordEncoder.matches(clientSecret, credential.secretHash())
                || !"active".equals(credential.credentialStatus())
                || (credential.credentialExpiresAt() != null && !credential.credentialExpiresAt().isAfter(now))
                || !"active".equals(credential.installationStatus())
                || !"active".equals(credential.applicationStatus())) {
            throw new InvalidClientException();
        }
        Set<String> granted = scopeMapper.findScopes(credential.tenantId(), credential.installationId());
        Set<String> requested = parseScopes(requestedScope);
        Set<String> issued = requested.isEmpty() ? new TreeSet<>(granted) : requested;
        if (!granted.containsAll(issued)) {
            throw new InvalidScopeException();
        }

        String rawToken = secretCodec.newAccessToken();
        ApplicationAccessToken token = new ApplicationAccessToken();
        token.setPid(UniqueIdGenerator.generate());
        token.setTenantId(credential.tenantId());
        token.setInstallationId(credential.installationId());
        token.setCredentialId(credential.credentialId());
        token.setTokenHash(secretCodec.sha256(rawToken));
        token.setScopes(toJson(issued));
        token.setAudience(AUDIENCE);
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(tokenTtl));
        tokenMapper.insert(token);
        authMapper.touchCredential(credential.credentialId(), now);
        return new TokenResponse(rawToken, "Bearer", tokenTtl.toSeconds(), String.join(" ", issued));
    }

    private Set<String> parseScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(scope.trim().split("\\s+"))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private String toJson(Set<String> scopes) {
        return scopes.stream().map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    public static class InvalidClientException extends RuntimeException {
    }

    public static class InvalidScopeException extends RuntimeException {
    }
}
