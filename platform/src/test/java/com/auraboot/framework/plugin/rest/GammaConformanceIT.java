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

    private HttpResponse<String> post(String path, String bearer, String body, String idempotencyKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int noteCount(String bearer) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/ext/probe/notes"))
                .header("Authorization", "Bearer " + bearer).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("list notes").isEqualTo(200);
        String body = resp.body();
        int i = body.indexOf("\"count\":") + "\"count\":".length();
        int j = i;
        while (j < body.length() && (Character.isDigit(body.charAt(j)))) j++;
        return Integer.parseInt(body.substring(i, j));
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

    // ── gamma-2: governed pipeline (schema + idempotency + transaction/audit) ───────────────

    @Test
    void echo_validBody_returns200WithEchoedPayload() throws Exception {
        HttpResponse<String> resp = post("/api/ext/probe/echo", login(), "{\"text\":\"hello\"}", null);
        assertThat(resp.statusCode()).as("schema-valid echo").isEqualTo(200);
        assertThat(resp.body()).contains("hello");
        assertThat(resp.headers().firstValue("X-Gamma-Probe")).contains("echo");
    }

    @Test
    void echo_schemaViolation_returns400_beforeHandlerRuns() throws Exception {
        // Missing required "text" → governed pipeline rejects with 400 (JSON-schema pre-validation).
        HttpResponse<String> resp = post("/api/ext/probe/echo", login(), "{}", null);
        assertThat(resp.statusCode()).as("schema-invalid echo is rejected pre-handler").isEqualTo(400);
        assertThat(resp.body()).contains("text");
    }

    @Test
    void echo_sameIdempotencyKey_replaysWithoutReexecuting() throws Exception {
        String token = login();
        String key = "gamma2-idem-" + System.nanoTime();
        HttpResponse<String> first = post("/api/ext/probe/echo", token, "{\"text\":\"once\"}", key);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("X-Idempotent-Replay")).isEmpty();

        HttpResponse<String> replay = post("/api/ext/probe/echo", token, "{\"text\":\"once\"}", key);
        assertThat(replay.statusCode()).as("idempotent replay still 200").isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("X-Idempotent-Replay"))
                .as("second call with same key is served from the idempotency ledger").contains("true");
    }

    @Test
    void boom_rollsBackTheWrite_andReturns500_withoutSwallowing() throws Exception {
        String token = login();
        int before = noteCount(token);

        HttpResponse<String> resp = post("/api/ext/probe/boom", token, "", null);
        assertThat(resp.statusCode())
                .as("handler threw → not swallowed → mapped to 500").isEqualTo(500);

        int after = noteCount(token);
        assertThat(after)
                .as("the probe_note write inside the failed handler was rolled back by the pipeline tx")
                .isEqualTo(before);
    }

    // ── gamma-3: binary/streaming response + public endpoints ───────────────────────────────

    @Test
    void reportCsv_returnsBinaryCsvBody() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/ext/probe/report.csv"))
                .header("Authorization", "Bearer " + login()).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("authenticated CSV download").isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).get().asString().contains("text/csv");
        assertThat(resp.body()).contains("id,text").contains("alpha");
        assertThat(resp.headers().firstValue("X-Gamma-Probe")).contains("report");
    }

    @Test
    void publicCheckin_servedWithoutAnyToken() throws Exception {
        // No Authorization header at all — the WhiteList exposes /api/ext/*/public/** and the
        // dispatcher binds a default-tenant public context.
        HttpResponse<String> resp = post("/api/ext/probe/public/checkin", null, "{}", null);
        assertThat(resp.statusCode()).as("PUBLIC route served unauthenticated").isEqualTo(200);
        assertThat(resp.body()).contains("\"public\":true");
        assertThat(resp.headers().firstValue("X-Gamma-Probe")).contains("public-checkin");
    }

    @Test
    void authenticatedRoute_stillRejectsMissingToken() throws Exception {
        // Regression: only /public/ is whitelisted — a non-public plugin route still needs auth.
        HttpResponse<String> resp = post("/api/ext/probe/echo", null, "{\"text\":\"x\"}", null);
        assertThat(resp.statusCode())
                .as("non-public plugin route without a token is still rejected").isEqualTo(401);
    }
}
