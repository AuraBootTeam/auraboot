package com.auraboot.framework.plugin.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gamma-1 conformance against a LIVE isolated docker stack with the {@code gamma-conformance}
 * dogfood plugin loaded. Env-gated so it never fires in unit CI — proves the
 * {@code RestEndpointExtension} SPI end-to-end: dispatcher + registry routing + JWT auth +
 * tenant-context injection + permission enforcement.
 *
 * <p>Run (after {@code start-isolated.sh --slug=gamma --e2e --rebuild} +
 * {@code import-plugins.sh --slug=gamma gamma-conformance}):
 * <pre>
 *   GAMMA_LIVE_IT=1 GAMMA_BE_PORT=6513 \
 *     ./gradlew :test --tests 'com.auraboot.framework.plugin.rest.GammaConformanceIT'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "GAMMA_LIVE_IT", matches = "1")
class GammaConformanceIT {

    private static final String BASE =
            "http://localhost:" + System.getenv().getOrDefault("GAMMA_BE_PORT", "6513");

    private final HttpClient http = HttpClient.newHttpClient();

    private String login() throws Exception {
        String admin = System.getenv().getOrDefault("GAMMA_ADMIN", "admin@auraboot.com");
        String pw = System.getenv().getOrDefault("GAMMA_PW", "Test2026x");
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + admin + "\",\"password\":\"" + pw + "\"}"))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("admin login").isEqualTo(200);
        String body = resp.body();
        int start = body.indexOf("\"jwt\":\"") + "\"jwt\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    private HttpResponse<String> whoami(String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/ext/probe/whoami")).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void noToken_returns401() throws Exception {
        assertThat(whoami(null).statusCode())
                .as("/api/ext/probe/whoami without a token is rejected by the security filter chain")
                .isEqualTo(401);
    }

    @Test
    void withAdminToken_returns200WithInjectedTenantAndUser() throws Exception {
        HttpResponse<String> resp = whoami(login());
        assertThat(resp.statusCode()).as("authenticated + permitted").isEqualTo(200);
        assertThat(resp.body())
                .as("handler returned the platform-injected tenant + user")
                .contains("\"tenantId\":")
                .contains("\"userId\":");
        assertThat(resp.headers().firstValue("X-Gamma-Probe"))
                .as("request was delegated to the plugin handler (not a platform fallback)")
                .contains("whoami");
    }
}
