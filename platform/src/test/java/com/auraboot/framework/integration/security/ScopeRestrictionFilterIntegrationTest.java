package com.auraboot.framework.integration.security;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.security.TokenScopePolicy;
import com.auraboot.framework.auth.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-HTTP contract for {@code ScopeRestrictionFilter}.
 *
 * <p>Deliberately not MockMvc. The behaviour under test is the difference between 401 and 500, and
 * that distinction is produced by the servlet container's handling of an exception thrown inside a
 * filter — MockMvc does not reproduce it faithfully. A random-port server does.
 *
 * <p>Baseline measured on 2026-07-13 before this filter existed: a visitor-shaped token aimed at
 * {@code /api/dynamic/**}, {@code /api/im/conversations}, {@code /api/meta/models} and
 * {@code /api/user/profile} answered <b>500</b> every time, because {@code JwtAuthenticationFilter}
 * calls {@code UnifiedUserDetailsService.loadUserByUsername} outside a try/catch and a visitor is
 * not a platform user. These tests pin the corrected answer: 401, a policy decision.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
class ScopeRestrictionFilterIntegrationTest {

    private static final String VISITOR_SCOPE = "visitor";
    private static final String ALLOWED_PREFIX = "/api/public/cs/**";

    @TestConfiguration
    static class TestScopePolicy {
        @Bean
        TokenScopePolicy visitorPolicy() {
            return new TokenScopePolicy() {
                @Override
                public String scope() {
                    return VISITOR_SCOPE;
                }

                @Override
                public String[] allowedPathPatterns() {
                    return new String[]{ALLOWED_PREFIX};
                }
            };
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TestRestTemplate rest;

    private String visitorToken() {
        return jwtUtil.generateScopedToken(
                "vis_01HTESTVISITOR0000000001",
                VISITOR_SCOPE,
                Map.of("tenantId", 1L, "visitorPid", "vis_01HTESTVISITOR0000000001", "siteId", 1L),
                600);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("visitor token on a business endpoint is 401 — not the 500 it used to be")
    void visitorTokenOnBusinessEndpointIsUnauthorized() {
        String token = visitorToken();

        for (String path : new String[]{
                "/api/dynamic/crm_lead/list",
                "/api/im/conversations",
                "/api/meta/models",
                "/api/user/profile"}) {
            ResponseEntity<String> response = get(path, token);

            assertThat(response.getStatusCode())
                    .as("visitor token must be turned away from %s as a policy decision, not an error", path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getStatusCode().value())
                    .as("a 500 here means the token reached the user lookup — the isolation is broken")
                    .isNotEqualTo(500);
        }
    }

    @Test
    @DisplayName("visitor token is not blocked on the path its policy allows")
    void visitorTokenPassesFilterOnAllowedPath() {
        ResponseEntity<String> response = get("/api/public/cs/session/ping", visitorToken());

        // No controller is mounted there in OSS — 404 is the right answer and proves the request
        // got past the scope filter. What matters is that it is not a 401.
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a scope with no policy is rejected — fail closed")
    void unknownScopeIsRejected() {
        String rogue = jwtUtil.generateScopedToken("someone", "not_a_registered_scope", Map.of(), 600);

        assertThat(get("/api/public/cs/session/ping", rogue).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/dynamic/crm_lead/list", rogue).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a visitor token cannot borrow a whitelisted path either")
    void visitorTokenIsConfinedEvenOnWhitelistedPaths() {
        // /api/auth/login is public, but the scope policy does not name it, so the scoped token has
        // no business there. Confinement is by allowlist, not by "is this path authenticated".
        assertThat(get("/api/auth/login/channels", visitorToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an ordinary request without a scope claim is untouched")
    void unscopedRequestsAreUnaffected() {
        // No Authorization header at all: the scope filter must not interfere — the response is
        // whatever the existing chain says (401 from JwtAuthenticationFilter for a guarded path).
        ResponseEntity<String> guarded = get("/api/user/profile", null);
        assertThat(guarded.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // And a public path with no token still works — proving the filter did not start demanding
        // tokens on endpoints that never needed them.
        ResponseEntity<String> publicPath = get("/api/auth/login/channels", null);
        assertThat(publicPath.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
