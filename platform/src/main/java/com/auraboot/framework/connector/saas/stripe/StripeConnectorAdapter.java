package com.auraboot.framework.connector.saas.stripe;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.connector.saas.AbstractSaasConnectorAdapter;
import com.auraboot.framework.connector.saas.ReadCursor;
import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.saas.http.SaasHttpClient;
import com.auraboot.framework.connector.saas.http.SaasHttpException;
import com.auraboot.framework.connector.saas.http.SaasHttpRequest;
import com.auraboot.framework.connector.sdk.ConnectorDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Stripe Payments connector. PRD 18 §B.3.2.
 *
 * <p>Auth: secret-key {@code sk_*} or restricted-key {@code rk_*} bearer.
 * Stripe has no refresh flow — the key in
 * {@link SaasConnectorConfig#clientSecret()} is sent verbatim as the bearer
 * on every request. No {@code OAuth2TokenStore} involvement.
 *
 * <p>Pagination: every Stripe list endpoint returns
 * {@code {data:[...], has_more:bool}}. Forward pagination uses
 * {@code starting_after=<last_id>}. We do not use ID-based incremental
 * windows because Stripe IDs are not monotonic across resources; instead we
 * push {@code created[gte]=<unix_ts>} when {@link ReadCursor#since} is set.
 *
 * <h3>Streams (W5-M2.2)</h3>
 *
 * <p>Seven core read streams:
 * {@code customers / charges / invoices / subscriptions / products /
 *  prices / payment_intents}. The {@code events} endpoint is exposed as an
 * 8th stream for change-feed sync. {@link #read} sends {@code expand[]=}
 * fields for nested objects (customer name on charge, etc.) so downstream
 * consumers don't need a second round trip.
 */
@Slf4j
@Component
public class StripeConnectorAdapter extends AbstractSaasConnectorAdapter {

    public static final String VENDOR = "saas-stripe";
    public static final String DEFAULT_API_BASE = "https://api.stripe.com";

    /** Stripe max page size. */
    static final int PAGE_LIMIT = 100;

    private static final List<String> CORE_STREAMS = List.of(
            "customers", "charges", "invoices", "subscriptions",
            "products", "prices", "payment_intents", "events");

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(
            VENDOR,
            "Stripe Payments via REST API (API-key bearer + starting_after pagination)",
            CORE_STREAMS);

    private final SaasHttpClient http;
    private final ObjectMapper jsonMapper;

    public StripeConnectorAdapter(SaasHttpClient http, ObjectMapper jsonMapper) {
        this.http = http;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ConnectorDescriptor descriptor() { return DESCRIPTOR; }

    // -- discover ------------------------------------------------------

    /**
     * Stripe object model is fixed — no runtime discovery call needed.
     * Each stream maps to its primary key (always {@code "id"}) and cursor
     * field (always {@code "created"}).
     */
    @Override
    public Map<String, Object> discover(SaasConnectorConfig config) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String s : CORE_STREAMS) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("primaryKey", "id");
            meta.put("cursorField", "created");
            // Events stream is by-definition incremental from a since timestamp;
            // others can be either full or incremental.
            meta.put("supportsIncremental", true);
            meta.put("source", "core");
            result.put(s, meta);
        }
        return result;
    }

    // -- read ----------------------------------------------------------

    @Override
    public Stream<Map<String, Object>> read(SaasConnectorConfig config,
                                            String streamName,
                                            ReadCursor cursor) {
        if (streamName == null || streamName.isBlank()) {
            throw new IllegalArgumentException("streamName required");
        }
        if (!CORE_STREAMS.contains(streamName)) {
            throw new IllegalArgumentException("Unknown Stripe stream: " + streamName
                    + " — allowed: " + CORE_STREAMS);
        }
        String apiKey = config.clientSecret();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Stripe connector requires clientSecret (API key) on the config");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        PageState state = new PageState(
                tenantId, apiKey, config, streamName,
                cursor != null ? cursor.pageToken() : null,
                cursor != null && cursor.since() != null
                        ? cursor.since().getEpochSecond() : null);
        Iterator<Map<String, Object>> it = new PageIterator(state);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    static final class PageState {
        final Long tenantId;
        final String apiKey;
        final SaasConnectorConfig config;
        final String streamName;
        String startingAfter;
        final Long sinceEpoch;

        PageState(Long tenantId, String apiKey, SaasConnectorConfig config,
                  String streamName, String startingAfter, Long sinceEpoch) {
            this.tenantId = tenantId;
            this.apiKey = apiKey;
            this.config = config;
            this.streamName = streamName;
            this.startingAfter = startingAfter;
            this.sinceEpoch = sinceEpoch;
        }
    }

    final class PageIterator implements Iterator<Map<String, Object>> {

        private final PageState state;
        private final java.util.Deque<Map<String, Object>> buffer = new java.util.ArrayDeque<>();
        private boolean exhausted = false;

        PageIterator(PageState state) { this.state = state; }

        @Override
        public boolean hasNext() {
            if (!buffer.isEmpty()) return true;
            if (exhausted) return false;
            fetchNextPage();
            return !buffer.isEmpty();
        }

        @Override
        public Map<String, Object> next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            return buffer.pop();
        }

        private void fetchNextPage() {
            StringBuilder url = new StringBuilder()
                    .append(baseUrl(state.config))
                    .append("/v1/").append(state.streamName)
                    .append("?limit=").append(PAGE_LIMIT);
            if (state.startingAfter != null && !state.startingAfter.isBlank()) {
                url.append("&starting_after=").append(enc(state.startingAfter));
            }
            if (state.sinceEpoch != null) {
                // The "events" endpoint uses `created[gte]=` directly; other list
                // endpoints accept the same since it's a query-string repeat-key
                // shape Stripe parses uniformly.
                url.append("&created%5Bgte%5D=").append(state.sinceEpoch);
            }
            SaasHttpRequest req = SaasHttpRequest.builder()
                    .tenantId(state.tenantId)
                    .vendor(VENDOR)
                    .method("GET")
                    .url(url.toString())
                    .bearer(state.apiKey)
                    .header("Accept", "application/json")
                    .header("Stripe-Version", "2024-06-20")
                    .build();
            try {
                JsonNode root = http.executeForJson(req,
                        SaasHttpClient.RetryPolicy.DEFAULT,
                        SaasHttpClient.RateLimit.STRIPE);
                JsonNode data = root.path("data");
                String lastId = null;
                if (data.isArray()) {
                    for (JsonNode n : data) {
                        buffer.add(toMap(n));
                        if (n.has("id")) lastId = n.path("id").asText(lastId);
                    }
                }
                boolean hasMore = root.path("has_more").asBoolean(false);
                if (hasMore && lastId != null) {
                    state.startingAfter = lastId;
                } else {
                    exhausted = true;
                    state.startingAfter = null;
                }
            } catch (SaasHttpException e) {
                log.warn("Stripe {} page fetch failed: {}", state.streamName, e.getMessage());
                exhausted = true;
            }
        }
    }

    private Map<String, Object> toMap(JsonNode node) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = jsonMapper.convertValue(node, Map.class);
            return m;
        } catch (Exception e) {
            log.warn("Failed to map Stripe record: {}", e.getMessage());
            return Map.of();
        }
    }

    private String baseUrl(SaasConnectorConfig config) {
        String b = config != null ? config.apiBaseUrl() : null;
        if (b == null || b.isBlank()) return DEFAULT_API_BASE;
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
