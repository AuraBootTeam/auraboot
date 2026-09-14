package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.openplatform.entity.ApplicationAccessToken;
import com.auraboot.framework.openplatform.mapper.ApplicationAccessTokenMapper;
import com.auraboot.framework.openplatform.mapper.ApplicationScopeGrantMapper;
import com.auraboot.framework.openplatform.mapper.OpenPlatformAuthMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenPlatformTokenServiceTest {
    private final OpenPlatformAuthMapper authMapper = mock(OpenPlatformAuthMapper.class);
    private final ApplicationScopeGrantMapper scopeMapper = mock(ApplicationScopeGrantMapper.class);
    private final ApplicationAccessTokenMapper tokenMapper = mock(ApplicationAccessTokenMapper.class);
    private final OpenPlatformSecretCodec codec = new OpenPlatformSecretCodec();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private OpenPlatformTokenService service;

    @BeforeEach
    void setUp() {
        service = new OpenPlatformTokenService(authMapper, scopeMapper, tokenMapper, codec, passwordEncoder);
        ReflectionTestUtils.setField(service, "tokenTtl", Duration.ofHours(1));
    }

    @Test
    void issuesOpaqueShortLivedTokenForGrantedScopeSubset() {
        String secret = "top-secret";
        when(authMapper.findCredential("client"))
                .thenReturn(activeCredential(passwordEncoder.encode(secret)));
        when(scopeMapper.findScopes(42L, 9L)).thenReturn(Set.of("records.read", "orders.write"));

        var response = service.issue("client_credentials", "client", secret, "records.read");

        assertTrue(response.accessToken().startsWith("ab_at_"));
        assertEquals("Bearer", response.tokenType());
        assertEquals(3600, response.expiresIn());
        assertEquals("records.read", response.scope());
        ArgumentCaptor<ApplicationAccessToken> token = ArgumentCaptor.forClass(ApplicationAccessToken.class);
        verify(tokenMapper).insert(token.capture());
        assertEquals(42L, token.getValue().getTenantId());
        assertEquals(codec.sha256(response.accessToken()), token.getValue().getTokenHash());
        assertEquals("[\"records.read\"]", token.getValue().getScopes());
    }

    @Test
    void rejectsUnknownScopeAndInvalidSecretWithoutIssuingToken() {
        String secret = "top-secret";
        when(authMapper.findCredential("client"))
                .thenReturn(activeCredential(passwordEncoder.encode(secret)));
        when(scopeMapper.findScopes(42L, 9L)).thenReturn(Set.of("records.read"));

        assertThrows(OpenPlatformTokenService.InvalidScopeException.class,
                () -> service.issue("client_credentials", "client", secret, "orders.write"));
        assertThrows(OpenPlatformTokenService.InvalidClientException.class,
                () -> service.issue("client_credentials", "client", "wrong", "records.read"));
    }

    private OpenPlatformAuthMapper.CredentialAuthRecord activeCredential(String hash) {
        return new OpenPlatformAuthMapper.CredentialAuthRecord(3L, "cred", 42L, 9L, hash,
                "active", Instant.now().plusSeconds(3600), "inst", "production", "active",
                "app", "active");
    }
}
