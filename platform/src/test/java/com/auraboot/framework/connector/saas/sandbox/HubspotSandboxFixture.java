package com.auraboot.framework.connector.saas.sandbox;

import com.auraboot.framework.connector.saas.http.SaasHttpRequest;
import com.auraboot.framework.connector.saas.http.SaasHttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HubSpot sandbox fixture.
 *
 * <p>Provides contract-style stub routes that simulate the HubSpot v3 CRM API
 * wire behaviour without a real API key:
 * <ul>
 *   <li>OAuth refresh-token grant with RT rotation (every call increments a
 *       counter; the new access and refresh tokens embed the counter).</li>
 *   <li>Six core CRM search streams ({@code contacts / companies / deals /
 *       tickets / line_items / products}) — each has 50 fixture rows × 3
 *       pages = 150 rows total, mirroring real HubSpot shape.</li>
 *   <li>Custom schema discovery ({@code GET /crm/v3/schemas}) returning two
 *       custom objects: {@code projects} and {@code invoices}.</li>
 *   <li>Server-side {@code filterGroups GTE} filtering that respects the
 *       {@code hs_lastmodifieddate} value in the request body.</li>
 * </ul>
 *
 * <h3>Observability</h3>
 * <ul>
 *   <li>{@link #getCalledEndpoints()} — via the wrapped executor</li>
 *   <li>{@link #getLastSearchBody(String)} — last parsed POST body for a given stream</li>
 *   <li>{@link #getOAuthRotationCount()} — number of successful /token calls</li>
 * </ul>
 */
public class HubspotSandboxFixture {

    static final String API_BASE = "https://api.hubapi.com";
    static final String TOKEN_URL = API_BASE + "/oauth/v1/token";

    // Configured client credentials the fixture will validate.
    private final String expectedClientId;
    private final String expectedClientSecret;
    private final String initialRefreshToken;

    private final ObjectMapper json = new ObjectMapper();

    /** Counter incremented every time /token is called successfully. */
    private final AtomicInteger rotationCount = new AtomicInteger(0);

    /** Last raw JSON body posted to each stream's /search endpoint. */
    private final Map<String, String> lastSearchBodies = new java.util.concurrent.ConcurrentHashMap<>();

    /** Fixed dataset per stream: stream → list of 150 rows. */
    private final Map<String, List<ObjectNode>> dataset;

    public HubspotSandboxFixture(String clientId, String clientSecret, String initialRefreshToken) {
        this.expectedClientId = clientId;
        this.expectedClientSecret = clientSecret;
        this.initialRefreshToken = initialRefreshToken;
        this.dataset = buildDataset();
    }

    // -- observability -------------------------------------------------------

    public int getOAuthRotationCount() {
        return rotationCount.get();
    }

    public String getLastSearchBody(String streamName) {
        return lastSearchBodies.get(streamName);
    }

    // -- route list ----------------------------------------------------------

    /**
     * Build and return a {@link SandboxHttpExecutor} wired up with all HubSpot
     * sandbox routes.
     */
    public SandboxHttpExecutor buildExecutor() {
        List<SandboxHttpExecutor.Route> routes = new ArrayList<>();

        // OAuth refresh endpoint.
        routes.add(new SandboxHttpExecutor.Route("POST", TOKEN_URL, this::handleTokenRefresh));

        // Custom schema discovery.
        routes.add(new SandboxHttpExecutor.Route("GET", API_BASE + "/crm/v3/schemas", this::handleSchemas));

        // Six core CRM search streams.
        for (String stream : List.of("contacts", "companies", "deals", "tickets", "line_items", "products")) {
            final String s = stream;
            routes.add(new SandboxHttpExecutor.Route("POST",
                    API_BASE + "/crm/v3/objects/" + s + "/search",
                    (req, vars) -> handleSearch(req, s)));
        }

        return new SandboxHttpExecutor(routes);
    }

    // -- handler implementations --------------------------------------------

    private SaasHttpResponse handleTokenRefresh(SaasHttpRequest req, Map<String, String> pathVars) {
        String body = bodyAsString(req);
        if (!body.contains("grant_type=refresh_token")) {
            return SandboxHttpExecutor.jsonStatus(400,
                    "{\"error\":\"invalid_grant_type\",\"message\":\"grant_type must be refresh_token\"}");
        }
        if (!body.contains("client_id=" + enc(expectedClientId))) {
            return SandboxHttpExecutor.jsonStatus(401,
                    "{\"error\":\"invalid_client\",\"message\":\"client_id mismatch\"}");
        }
        if (!body.contains("client_secret=" + enc(expectedClientSecret))) {
            return SandboxHttpExecutor.jsonStatus(401,
                    "{\"error\":\"invalid_client\",\"message\":\"client_secret mismatch\"}");
        }

        int n = rotationCount.incrementAndGet();
        // Extract the incoming refresh token from the body to verify rotation.
        String incomingRt = extractFormParam(body, "refresh_token");

        // New tokens embed the rotation counter so callers can assert they received
        // the rotated values.
        String newAccessToken = "new-" + incomingRt + "-" + n;
        String newRefreshToken = "rt-" + n;

        String responseBody = json.createObjectNode()
                .put("access_token", newAccessToken)
                .put("refresh_token", newRefreshToken)
                .put("expires_in", 1800)
                .put("token_type", "bearer")
                .put("scope", "crm.objects.contacts.read crm.objects.companies.read")
                .toString();
        return SandboxHttpExecutor.jsonOk(responseBody);
    }

    private SaasHttpResponse handleSchemas(SaasHttpRequest req, Map<String, String> pathVars) {
        ObjectNode projects = json.createObjectNode()
                .put("name", "projects")
                .put("objectTypeId", "2-12345")
                .put("primaryDisplayProperty", "project_name");
        ObjectNode invoices = json.createObjectNode()
                .put("name", "invoices")
                .put("objectTypeId", "2-67890")
                .put("primaryDisplayProperty", "invoice_number");
        ArrayNode results = json.createArrayNode().add(projects).add(invoices);
        return SandboxHttpExecutor.jsonOk(json.createObjectNode().set("results", results).toString());
    }

    private SaasHttpResponse handleSearch(SaasHttpRequest req, String stream) {
        // Record the raw body for assertion.
        String rawBody = bodyAsString(req);
        lastSearchBodies.put(stream, rawBody);

        // Parse the request body to extract pagination cursor + since filter.
        JsonNode reqBody;
        try {
            reqBody = json.readTree(rawBody.isBlank() ? "{}" : rawBody);
        } catch (Exception e) {
            return SandboxHttpExecutor.jsonStatus(400, "{\"error\":\"invalid_body\"}");
        }

        int limit = reqBody.path("limit").asInt(100);
        String afterCursor = reqBody.path("after").asText(null);
        if (afterCursor != null && afterCursor.isBlank()) afterCursor = null;

        // Optional filterGroups GTE on hs_lastmodifieddate.
        Instant sinceFilter = extractSinceFilter(reqBody);

        List<ObjectNode> rows = dataset.getOrDefault(stream, Collections.emptyList());

        // Apply since filter (server-side) if present.
        if (sinceFilter != null) {
            final Instant since = sinceFilter;
            rows = rows.stream()
                    .filter(r -> {
                        String ts = r.path("properties").path("hs_lastmodifieddate").asText(null);
                        if (ts == null) return false;
                        try {
                            return !Instant.parse(ts).isBefore(since);
                        } catch (Exception ex) {
                            return false;
                        }
                    })
                    .collect(java.util.stream.Collectors.toList());
        }

        // Determine the page offset from the after cursor.
        int offset = 0;
        if (afterCursor != null) {
            try {
                offset = Integer.parseInt(afterCursor);
            } catch (NumberFormatException ex) {
                offset = 0;
            }
        }

        int pageSize = Math.min(limit, 50); // sandbox pages at 50 max
        int from = Math.min(offset, rows.size());
        int to = Math.min(from + pageSize, rows.size());
        List<ObjectNode> page = rows.subList(from, to);

        ObjectNode response = json.createObjectNode();
        ArrayNode results = json.createArrayNode();
        for (ObjectNode row : page) {
            results.add(row);
        }
        response.set("results", results);

        // Add paging cursor if more results remain.
        if (to < rows.size()) {
            ObjectNode paging = json.createObjectNode();
            ObjectNode next = json.createObjectNode();
            next.put("after", String.valueOf(to));
            paging.set("next", next);
            response.set("paging", paging);
        } else {
            response.set("paging", json.createObjectNode());
        }
        response.put("total", rows.size());

        return SandboxHttpExecutor.jsonOk(response.toString());
    }

    // -- dataset builder ----------------------------------------------------

    private Map<String, List<ObjectNode>> buildDataset() {
        Map<String, List<ObjectNode>> map = new java.util.LinkedHashMap<>();
        map.put("contacts", buildContactRows(150));
        map.put("companies", buildGenericRows("companies", 150));
        map.put("deals", buildGenericRows("deals", 150));
        map.put("tickets", buildGenericRows("tickets", 150));
        map.put("line_items", buildGenericRows("line_items", 150));
        map.put("products", buildGenericRows("products", 150));
        return Collections.unmodifiableMap(map);
    }

    private List<ObjectNode> buildContactRows(int count) {
        List<ObjectNode> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ObjectNode props = json.createObjectNode()
                    .put("firstname", "FirstName" + i)
                    .put("lastname", "LastName" + i)
                    .put("email", "user" + i + "@example.com")
                    .put("phone", "+1-555-" + String.format("%04d", i))
                    .put("hs_lastmodifieddate",
                            Instant.parse("2026-01-01T00:00:00Z")
                                    .plus(i, ChronoUnit.HOURS).toString())
                    .put("createdate",
                            Instant.parse("2025-01-01T00:00:00Z")
                                    .plus(i, ChronoUnit.DAYS).toString());
            ObjectNode row = json.createObjectNode()
                    .put("id", String.valueOf(1000 + i));
            row.set("properties", props);
            rows.add(row);
        }
        return Collections.unmodifiableList(rows);
    }

    private List<ObjectNode> buildGenericRows(String stream, int count) {
        List<ObjectNode> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ObjectNode props = json.createObjectNode()
                    .put("name", stream + "_record_" + i)
                    .put("hs_lastmodifieddate",
                            Instant.parse("2026-01-01T00:00:00Z")
                                    .plus(i, ChronoUnit.HOURS).toString())
                    .put("createdate",
                            Instant.parse("2025-01-01T00:00:00Z")
                                    .plus(i, ChronoUnit.DAYS).toString());
            ObjectNode row = json.createObjectNode()
                    .put("id", String.valueOf(5000 + i));
            row.set("properties", props);
            rows.add(row);
        }
        return Collections.unmodifiableList(rows);
    }

    // -- helpers ------------------------------------------------------------

    private static Instant extractSinceFilter(JsonNode reqBody) {
        JsonNode fg = reqBody.path("filterGroups");
        if (!fg.isArray() || fg.isEmpty()) return null;
        for (JsonNode group : fg) {
            for (JsonNode filter : group.path("filters")) {
                if ("hs_lastmodifieddate".equals(filter.path("propertyName").asText())
                        && "GTE".equals(filter.path("operator").asText())) {
                    String val = filter.path("value").asText(null);
                    if (val != null && !val.isBlank()) {
                        try { return Instant.parse(val); } catch (Exception ex) { return null; }
                    }
                }
            }
        }
        return null;
    }

    private static String bodyAsString(SaasHttpRequest req) {
        if (req.body() == null) return "";
        Object b = req.body();
        if (b instanceof String s) return s;
        if (b instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        // For ObjectNode / Map bodies serialised by the executor — use toString().
        try {
            return new ObjectMapper().writeValueAsString(b);
        } catch (Exception e) {
            return b.toString();
        }
    }

    private static String extractFormParam(String body, String key) {
        for (String part : body.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && key.equals(kv[0])) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String enc(String s) {
        if (s == null) return "";
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
