package com.auraboot.framework.coreliteit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable base for LIVE core-lite integration tests against an isolated OSS-core stack
 * started by scripts/dev/core-lite-it.sh. Subclasses are env-gated (CORE_LITE_IT=1) so they
 * never fire in unit CI. Proves the W2 path: import-directory-sync -> model-driven DDL builds
 * tables -> assert via real DynamicController CRUD + plugin Command APIs.
 *
 * <p>Tenant isolation: each subclass logs in as an admin of a specific tenant; rows are
 * tenant-scoped by the platform, so two tenants on the same physical tables do not collide.
 *
 * <p>Run (after scripts/dev/core-lite-it.sh --slug=core-lite --jars-dir=... --plugin=...):
 * <pre>
 *   CORE_LITE_IT=1 CORE_LITE_BE_PORT=&lt;port&gt; \
 *     ./gradlew test --tests 'com.auraboot.framework.coreliteit.BomCoreLiteHarnessIT'
 * </pre>
 */
public abstract class AbstractCoreLiteIT {

    protected static String basePort() {
        return System.getenv().getOrDefault("CORE_LITE_BE_PORT", "6443");
    }

    protected static String base() {
        return "http://localhost:" + basePort();
    }

    protected final HttpClient http = HttpClient.newHttpClient();

    // ---- pure helpers (unit-tested) ----

    /**
     * Substring extract of {@code "jwt":"..."} — works whether or not it is nested under data.
     * Login response structure: {@code {"code":"0","data":{"jwt":"...","tenantId":"..."},...}}
     * The jwt key is found by simple indexOf regardless of nesting depth.
     */
    static String parseJwt(String body) {
        int s = body.indexOf("\"jwt\":\"") + "\"jwt\":\"".length();
        return body.substring(s, body.indexOf('"', s));
    }

    /**
     * tenantId is a QUOTED string in the login envelope (may be a 64-bit snowflake id like
     * "319688643885273088" — do NOT parse to long, return verbatim as String).
     */
    static String parseTenantId(String body) {
        String key = "\"tenantId\":\"";
        int s = body.indexOf(key) + key.length();
        return body.substring(s, body.indexOf('"', s));
    }

    static String dynamicListPath(String pageKey) {
        return "/api/dynamic/" + pageKey + "/list";
    }

    // ---- live helpers (used by env-gated subclasses only) ----

    protected String login(String email, String pw) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"" + pw + "\"}"))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("login " + email).isEqualTo(200);
        return parseJwt(resp.body());
    }

    protected String adminLogin() throws Exception {
        return login(
                System.getenv().getOrDefault("CORE_LITE_ADMIN", "admin@auraboot.com"),
                System.getenv().getOrDefault("CORE_LITE_PW", "Test2026x"));
    }

    /**
     * Import a plugin config dir (container path) via the real import API.
     * Returns the raw response; caller asserts {@code success:true}.
     */
    protected HttpResponse<String> importPluginDir(String bearer, String containerPath) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(base() + "/api/plugins/import/import-directory-sync"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"path\":\"" + containerPath + "\",\"overwrite\":true}"))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Call DynamicController list for a given pageKey.
     *
     * <p>NOTE: verify GET vs POST + exact param names in Task 3 live run (red line #5).
     * Default is POST with JSON body until calibrated.
     */
    protected HttpResponse<String> dynamicList(String bearer, String pageKey, String filtersJson)
            throws Exception {
        String bodyStr = filtersJson != null
                ? filtersJson
                : "{\"pageNum\":1,\"pageSize\":20}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + dynamicListPath(pageKey)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
