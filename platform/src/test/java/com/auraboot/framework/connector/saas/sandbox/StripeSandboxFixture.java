package com.auraboot.framework.connector.saas.sandbox;

import com.auraboot.framework.connector.saas.http.SaasHttpRequest;
import com.auraboot.framework.connector.saas.http.SaasHttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stripe sandbox fixture.
 *
 * <p>Simulates the Stripe v1 list API wire behaviour:
 * <ul>
 *   <li>Seven streams: {@code customers / charges / invoices / subscriptions /
 *       products / prices / payment_intents} — each with 50 fixture rows × 3
 *       pages = 150 rows total.</li>
 *   <li>{@code has_more / starting_after} true forward-pagination semantics.</li>
 *   <li>Request validation: {@code Authorization: Bearer sk_test_*} required;
 *       {@code Stripe-Version: 2024-06-20} header recorded for assertion.</li>
 *   <li>{@code created[gte]=<epoch>} server-side filter respected.</li>
 *   <li>Invalid API key → 401 response with Stripe error envelope.</li>
 * </ul>
 *
 * <h3>Observability</h3>
 * <ul>
 *   <li>{@link #getCalledEndpoints()} — via the wrapped executor</li>
 *   <li>{@link #getLastStripeVersionHeader()} — last recorded Stripe-Version header value</li>
 *   <li>{@link #getRequestCount()} — total request count</li>
 * </ul>
 */
public class StripeSandboxFixture {

    static final String API_BASE = "https://api.stripe.com";

    /** The expected API key prefix — we accept any key starting with sk_test_. */
    private static final String TEST_KEY_PREFIX = "sk_test_";

    private final ObjectMapper json = new ObjectMapper();

    /** Last Stripe-Version header seen on any request. */
    private volatile String lastStripeVersionHeader;

    /** Fixed dataset per stream: stream → list of 150 rows. */
    private final Map<String, List<ObjectNode>> dataset;

    /** Last query-string seen per stream (for assertion). */
    private final ConcurrentHashMap<String, String> lastQueryPerStream = new ConcurrentHashMap<>();

    public StripeSandboxFixture() {
        this.dataset = buildDataset();
    }

    // -- observability -------------------------------------------------------

    public String getLastStripeVersionHeader() {
        return lastStripeVersionHeader;
    }

    public String getLastQueryString(String stream) {
        return lastQueryPerStream.get(stream);
    }

    // -- route list ----------------------------------------------------------

    public SandboxHttpExecutor buildExecutor() {
        List<SandboxHttpExecutor.Route> routes = new ArrayList<>();

        for (String stream : List.of("customers", "charges", "invoices", "subscriptions",
                "products", "prices", "payment_intents")) {
            final String s = stream;
            routes.add(new SandboxHttpExecutor.Route("GET",
                    API_BASE + "/v1/" + s,
                    (req, vars) -> handleList(req, s)));
        }

        return new SandboxHttpExecutor(routes);
    }

    // -- handler implementations --------------------------------------------

    private SaasHttpResponse handleList(SaasHttpRequest req, String stream) {
        // Validate API key.
        String auth = req.headers().get("Authorization");
        if (auth == null || !auth.startsWith("Bearer " + TEST_KEY_PREFIX)) {
            return SandboxHttpExecutor.jsonStatus(401,
                    "{\"error\":{\"type\":\"authentication_error\"," +
                    "\"message\":\"No such API key: " +
                    (auth != null ? auth : "null") + "\"}}");
        }

        // Record Stripe-Version header.
        String sv = req.headers().get("Stripe-Version");
        if (sv != null) lastStripeVersionHeader = sv;

        // Parse query string.
        String url = req.url();
        String query = url.contains("?") ? url.substring(url.indexOf('?') + 1) : "";
        lastQueryPerStream.put(stream, query);

        Map<String, String> params = parseQuery(query);
        String startingAfter = params.get("starting_after");
        String createdGteRaw = params.get("created%5Bgte%5D"); // URL-encoded created[gte]
        if (createdGteRaw == null) createdGteRaw = params.get("created[gte]");
        int limit = 100;
        try { limit = Integer.parseInt(params.getOrDefault("limit", "100")); } catch (NumberFormatException ignored) {}

        List<ObjectNode> rows = dataset.getOrDefault(stream, Collections.emptyList());

        // Apply created[gte] server-side filter.
        if (createdGteRaw != null && !createdGteRaw.isBlank()) {
            try {
                final long gteEpoch = Long.parseLong(createdGteRaw);
                rows = rows.stream()
                        .filter(r -> r.path("created").asLong(0) >= gteEpoch)
                        .collect(java.util.stream.Collectors.toList());
            } catch (NumberFormatException ignored) {}
        }

        // Determine starting offset from starting_after (last id of previous page).
        int offset = 0;
        if (startingAfter != null && !startingAfter.isBlank()) {
            for (int i = 0; i < rows.size(); i++) {
                if (startingAfter.equals(rows.get(i).path("id").asText())) {
                    offset = i + 1;
                    break;
                }
            }
        }

        int pageSize = Math.min(limit, 50); // sandbox pages at 50 max
        int from = Math.min(offset, rows.size());
        int to = Math.min(from + pageSize, rows.size());
        List<ObjectNode> page = rows.subList(from, to);

        boolean hasMore = to < rows.size();

        ObjectNode response = json.createObjectNode();
        response.put("object", "list");
        response.put("url", "/v1/" + stream);
        response.put("has_more", hasMore);
        ArrayNode data = json.createArrayNode();
        for (ObjectNode row : page) {
            data.add(row);
        }
        response.set("data", data);

        return SandboxHttpExecutor.jsonOk(response.toString());
    }

    // -- dataset builder ----------------------------------------------------

    private Map<String, List<ObjectNode>> buildDataset() {
        Map<String, List<ObjectNode>> map = new java.util.LinkedHashMap<>();
        map.put("customers", buildCustomerRows(150));
        map.put("charges", buildChargeRows(150));
        map.put("invoices", buildInvoiceRows(150));
        map.put("subscriptions", buildSubscriptionRows(150));
        map.put("products", buildProductRows(150));
        map.put("prices", buildPriceRows(150));
        map.put("payment_intents", buildPaymentIntentRows(150));
        return Collections.unmodifiableMap(map);
    }

    private List<ObjectNode> buildCustomerRows(int count) {
        List<ObjectNode> rows = new ArrayList<>(count);
        long baseEpoch = Instant.parse("2025-01-01T00:00:00Z").getEpochSecond();
        for (int i = 0; i < count; i++) {
            ObjectNode row = json.createObjectNode()
                    .put("id", "cus_" + String.format("%08d", i))
                    .put("object", "customer")
                    .put("name", "Customer " + i)
                    .put("email", "customer" + i + "@example.com")
                    .put("currency", "usd")
                    .put("created", baseEpoch + (long) i * 3600)
                    .put("livemode", false);
            rows.add(row);
        }
        return Collections.unmodifiableList(rows);
    }

    private List<ObjectNode> buildChargeRows(int count) {
        List<ObjectNode> rows = new ArrayList<>(count);
        long baseEpoch = Instant.parse("2025-01-01T00:00:00Z").getEpochSecond();
        for (int i = 0; i < count; i++) {
            ObjectNode row = json.createObjectNode()
                    .put("id", "ch_" + String.format("%08d", i))
                    .put("object", "charge")
                    .put("amount", (i + 1) * 1000)
                    .put("currency", "usd")
                    .put("status", "succeeded")
                    .put("customer", "cus_" + String.format("%08d", i % 50))
                    .put("created", baseEpoch + (long) i * 3600)
                    .put("livemode", false);
            rows.add(row);
        }
        return Collections.unmodifiableList(rows);
    }

    private List<ObjectNode> buildInvoiceRows(int count) {
        List<ObjectNode> rows = new ArrayList<>(count);
        long baseEpoch = Instant.parse("2025-01-01T00:00:00Z").getEpochSecond();
        for (int i = 0; i < count; i++) {
            ObjectNode row = json.createObjectNode()
                    .put("id", "in_" + String.format("%08d", i))
                    .put("object", "invoice")
                    .put("amount_due", (i + 1) * 2000)
                    .put("currency", "usd")
                    .put("status", i % 3 == 0 ? "paid" : "open")
                    .put("customer", "cus_" + String.format("%08d", i % 50))
                    .put("created", baseEpoch + (long) i * 3600)
                    .put("livemode", false);
            rows.add(row);
        }
        return Collections.unmodifiableList(rows);
    }

    private List<ObjectNode> buildSubscriptionRows(int count) {
        List<ObjectNode> rows = new ArrayList<>(count);
        long baseEpoch = Instant.parse("2025-01-01T00:00:00Z").getEpochSecond();
        for (int i = 0; i < count; i++) {
            ObjectNode row = json.createObjectNode()
                    .put("id", "sub_" + String.format("%08d", i))
                    .put("object", "subscription")
                    .put("status", "active")
                    .put("customer", "cus_" + String.format("%08d", i % 50))
                    .put("current_period_start", baseEpoch + (long) i * 3600)
                    .put("current_period_end", baseEpoch + (long) i * 3600 + 2592000L)
                    .put("created", baseEpoch + (long) i * 3600)
                    .put("livemode", false);
            rows.add(row);
        }
        return Collections.unmodifiableList(rows);
    }

    private List<ObjectNode> buildProductRows(int count) {
        List<ObjectNode> rows = new ArrayList<>(count);
        long baseEpoch = Instant.parse("2025-01-01T00:00:00Z").getEpochSecond();
        for (int i = 0; i < count; i++) {
            ObjectNode row = json.createObjectNode()
                    .put("id", "prod_" + String.format("%08d", i))
                    .put("object", "product")
                    .put("name", "Product " + i)
                    .put("active", true)
                    .put("created", baseEpoch + (long) i * 3600)
                    .put("livemode", false);
            rows.add(row);
        }
        return Collections.unmodifiableList(rows);
    }

    private List<ObjectNode> buildPriceRows(int count) {
        List<ObjectNode> rows = new ArrayList<>(count);
        long baseEpoch = Instant.parse("2025-01-01T00:00:00Z").getEpochSecond();
        for (int i = 0; i < count; i++) {
            ObjectNode row = json.createObjectNode()
                    .put("id", "price_" + String.format("%08d", i))
                    .put("object", "price")
                    .put("unit_amount", (i + 1) * 500)
                    .put("currency", "usd")
                    .put("product", "prod_" + String.format("%08d", i % 50))
                    .put("active", true)
                    .put("created", baseEpoch + (long) i * 3600)
                    .put("livemode", false);
            rows.add(row);
        }
        return Collections.unmodifiableList(rows);
    }

    private List<ObjectNode> buildPaymentIntentRows(int count) {
        List<ObjectNode> rows = new ArrayList<>(count);
        long baseEpoch = Instant.parse("2025-01-01T00:00:00Z").getEpochSecond();
        for (int i = 0; i < count; i++) {
            ObjectNode row = json.createObjectNode()
                    .put("id", "pi_" + String.format("%08d", i))
                    .put("object", "payment_intent")
                    .put("amount", (i + 1) * 1000)
                    .put("currency", "usd")
                    .put("status", "succeeded")
                    .put("customer", "cus_" + String.format("%08d", i % 50))
                    .put("created", baseEpoch + (long) i * 3600)
                    .put("livemode", false);
            rows.add(row);
        }
        return Collections.unmodifiableList(rows);
    }

    // -- helpers ------------------------------------------------------------

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        if (query == null || query.isBlank()) return params;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], kv[1]);
            } else if (kv.length == 1) {
                params.put(kv[0], "");
            }
        }
        return params;
    }
}
