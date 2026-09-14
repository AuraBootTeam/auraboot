package com.auraboot.framework.openplatform.security;

import com.auraboot.framework.application.security.ExternalApiKeyException;
import com.auraboot.framework.openplatform.mapper.OpenApiCallAuditMapper;
import com.auraboot.framework.openplatform.mapper.OpenPlatformAuthMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiRateLimitMapper;
import com.auraboot.framework.openplatform.service.OpenApiCapabilityRegistry;
import com.auraboot.framework.openplatform.service.OpenPlatformSecretCodec;
import com.auraboot.framework.openplatform.service.OpenPlatformTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class OpenPlatformBearerAuthenticatorTest {
    private final OpenPlatformAuthMapper authMapper = mock(OpenPlatformAuthMapper.class);
    private final OpenPlatformSecretCodec codec = new OpenPlatformSecretCodec();
    private final OpenApiRateLimitMapper rateLimitMapper = mock(OpenApiRateLimitMapper.class);
    private final OpenPlatformBearerAuthenticator authenticator = new OpenPlatformBearerAuthenticator(
            authMapper, mock(OpenApiCallAuditMapper.class), rateLimitMapper,
            new OpenApiCapabilityRegistry(List.of()),
            codec, new ObjectMapper());

    @Test
    void derivesTenantFromTokenAndIgnoresSpoofedTenantHeader() {
        when(authMapper.findToken(eq(codec.sha256("token")), eq(OpenPlatformTokenService.AUDIENCE),
                any(Instant.class)))
                .thenReturn(new OpenPlatformAuthMapper.TokenAuthRecord("tok", 42L, 9L,
                        "[\"openapi.profile.read\"]", Instant.now().plusSeconds(60), "inst",
                        "production", 600, "active", "app", "active"));
        when(rateLimitMapper.consume(eq(9L), any(Instant.class), eq(600))).thenReturn(1);
        MockHttpServletRequest request = request("GET", "/api/open/v1/whoami");
        request.addHeader("Authorization", "Bearer token");
        request.addHeader("X-Tenant-Id", "999");

        var principal = authenticator.authenticate(request);

        assertEquals(42L, principal.tenantId());
        assertEquals("inst", principal.installationPid());
    }

    @Test
    void failsClosedForUnpublishedRouteBeforeTokenLookup() {
        MockHttpServletRequest request = request("GET", "/api/open/v1/internal/users");
        request.addHeader("Authorization", "Bearer token");

        ExternalApiKeyException error = assertThrows(ExternalApiKeyException.class,
                () -> authenticator.authenticate(request));
        assertEquals(404, error.status());
    }

    @Test
    void rejectsTokenWithoutRequiredScope() {
        when(authMapper.findToken(eq(codec.sha256("token")), eq(OpenPlatformTokenService.AUDIENCE),
                any(Instant.class)))
                .thenReturn(new OpenPlatformAuthMapper.TokenAuthRecord("tok", 42L, 9L,
                        "[]", Instant.now().plusSeconds(60), "inst", "production", 600,
                        "active", "app", "active"));
        MockHttpServletRequest request = request("GET", "/api/open/v1/whoami");
        request.addHeader("Authorization", "Bearer token");

        ExternalApiKeyException error = assertThrows(ExternalApiKeyException.class,
                () -> authenticator.authenticate(request));
        assertEquals(403, error.status());
    }

    @Test
    void rejectsRequestWhenInstallationRateWindowIsExhausted() {
        when(authMapper.findToken(eq(codec.sha256("limited")), eq(OpenPlatformTokenService.AUDIENCE),
                any(Instant.class)))
                .thenReturn(new OpenPlatformAuthMapper.TokenAuthRecord("tok", 42L, 9L,
                        "[\"openapi.profile.read\"]", Instant.now().plusSeconds(60), "inst",
                        "production", 1, "active", "app", "active"));
        when(rateLimitMapper.consume(eq(9L), any(Instant.class), eq(1))).thenReturn(null);
        MockHttpServletRequest request = request("GET", "/api/open/v1/whoami");
        request.addHeader("Authorization", "Bearer limited");

        ExternalApiKeyException error = assertThrows(ExternalApiKeyException.class,
                () -> authenticator.authenticate(request));
        assertEquals(429, error.status());
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
