package com.auraboot.framework.connector.saas.hubspot;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.connector.saas.AbstractSaasConnectorAdapter;
import com.auraboot.framework.connector.saas.ReadCursor;
import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.saas.http.SaasHttpClient;
import com.auraboot.framework.connector.saas.http.SaasHttpException;
import com.auraboot.framework.connector.saas.http.SaasHttpRequest;
import com.auraboot.framework.connector.saas.oauth.OAuth2TokenStore;
import com.auraboot.framework.connector.sdk.ConnectorDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * HubSpot CRM connector. PRD 18 §B.3.2.
 *
 * <p>Auth: OAuth2 authorization-code; access token refreshed by
 * {@link HubspotTokenRefresher}. Sync path uses the v3 search endpoint
 * {@code POST /crm/v3/objects/{type}/search} so we can filter on
 * {@code hs_lastmodifieddate} for incremental pulls.
 *
 * <p>Pagination: {@code paging.next.after} from the response feeds back as
 * {@code after} in the next request body. The returned {@link Stream} is
 * lazy: each page is fetched on demand when the iterator is advanced.
 *
 * <p>Rate limit honoured via {@link SaasHttpClient.RateLimit#HUBSPOT}
 * (100/10s). 429 + {@code Retry-After} is handled inside the client.
 *
 * <h3>Streams (W5-M2.1)</h3>
 *
 * <p>Six core CRM objects exposed by HubSpot v3:
 * {@code companies / contacts / deals / tickets / line_items / products}.
 * Custom-object discovery happens via {@code GET /crm/v3/schemas} —
 * {@link #discover} merges custom schemas in alongside the core list.
 */
@Slf4j
@Component
public class HubspotConnectorAdapter extends AbstractSaasConnectorAdapter {

    public static final String VENDOR = HubspotTokenRefresher.VENDOR;
    public static final String DEFAULT_API_BASE = "https://api.hubapi.com";

    /** HubSpot search max is 100. */
    static final int PAGE_LIMIT = 100;

    private static final List<String> CORE_STREAMS = List.of(
            "companies", "contacts", "deals", "tickets", "line_items", "products");

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(
            VENDOR,
            "HubSpot CRM via v3 REST API (OAuth2 + search with paging.next.after)",
            CORE_STREAMS);

    private final SaasHttpClient http;
    private final OAuth2TokenStore tokenStore;
    private final ObjectMapper jsonMapper;

    public HubspotConnectorAdapter(SaasHttpClient http,
                                   OAuth2TokenStore tokenStore,
                                   ObjectMapper jsonMapper) {
        this.http = http;
        this.tokenStore = tokenStore;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ConnectorDescriptor descriptor() { return DESCRIPTOR; }

    // -- discovery ------------------------------------------------------

    /**
     * Enumerate objects this connector can sync — the 6 core HubSpot CRM
     * objects plus any custom objects the tenant has defined.
     *
     * <p>Returns {@code streamName → metadata}, where metadata carries at
     * minimum:
     * <ul>
     *   <li>{@code primaryKey} — always {@code "id"} for HubSpot.</li>
     *   <li>{@code cursorField} — always {@code "hs_lastmodifieddate"}.</li>
     *   <li>{@code supportsIncremental} — always {@code true}.</li>
     *   <li>{@code source} — {@code "core"} or {@code "custom"}.</li>
     * </ul>
     */
    @Override
    public Map<String, Object> discover(SaasConnectorConfig config) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String s : CORE_STREAMS) {
            result.put(s, streamMetadata("core"));
        }
        String access = tokenStore.getValidAccessToken(MetaContext.getCurrentTenantId(), config);
        SaasHttpRequest req = SaasHttpRequest.builder()
                .tenantId(MetaContext.getCurrentTenantId())
                .vendor(VENDOR)
                .method("GET")
                .url(baseUrl(config) + "/crm/v3/schemas")
                .bearer(access)
                .header("Accept", "application/json")
                .build();
        try {
            JsonNode root = http.executeForJson(req,
                    SaasHttpClient.RetryPolicy.DEFAULT,
                    SaasHttpClient.RateLimit.HUBSPOT);
            JsonNode results = root.path("results");
            if (results.isArray()) {
                for (JsonNode schema : results) {
                    String name = schema.path("name").asText("");
                    if (name.isBlank()) continue;
                    result.put(name, streamMetadata("custom"));
                }
            }
        } catch (SaasHttpException e) {
            // Custom schemas failure is non-fatal — core streams are usable
            // even without custom object discovery.
            log.warn("HubSpot custom-schema discovery failed: {}", e.getMessage());
        }
        return result;
    }

    private static Map<String, Object> streamMetadata(String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("primaryKey", "id");
        m.put("cursorField", "hs_lastmodifieddate");
        m.put("supportsIncremental", true);
        m.put("source", source);
        return m;
    }

    // -- read -----------------------------------------------------------

    /**
     * Lazy paginated read. The returned stream fetches one page per
     * advancement of the iterator; the {@code after} cursor flows through
     * the spliterator state.
     */
    @Override
    public Stream<Map<String, Object>> read(SaasConnectorConfig config,
                                            String streamName,
                                            ReadCursor cursor) {
        if (streamName == null || streamName.isBlank()) {
            throw new IllegalArgumentException("streamName required");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        String access = tokenStore.getValidAccessToken(tenantId, config);
        PageState state = new PageState(
                tenantId, access, config, streamName,
                cursor != null ? cursor.pageToken() : null,
                cursor != null ? cursor.since() : null);
        Iterator<Map<String, Object>> it = new PageIterator(state);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    /** Mutable iterator state — lives for the duration of one {@link #read} call. */
    static final class PageState {
        final Long tenantId;
        final String accessToken;
        final SaasConnectorConfig config;
        final String streamName;
        String afterCursor;
        final Instant since;

        PageState(Long tenantId, String accessToken, SaasConnectorConfig config,
                  String streamName, String afterCursor, Instant since) {
            this.tenantId = tenantId;
            this.accessToken = accessToken;
            this.config = config;
            this.streamName = streamName;
            this.afterCursor = afterCursor;
            this.since = since;
        }
    }

    /** Paged record iterator over {@code POST /crm/v3/objects/{stream}/search}. */
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
            ObjectNode requestBody = jsonMapper.createObjectNode();
            requestBody.put("limit", PAGE_LIMIT);
            if (state.afterCursor != null) {
                requestBody.put("after", state.afterCursor);
            }
            ArrayNode sorts = requestBody.putArray("sorts");
            ObjectNode sort = sorts.addObject();
            sort.put("propertyName", "hs_lastmodifieddate");
            sort.put("direction", "ASCENDING");
            if (state.since != null) {
                ArrayNode filterGroups = requestBody.putArray("filterGroups");
                ObjectNode group = filterGroups.addObject();
                ArrayNode filters = group.putArray("filters");
                ObjectNode filter = filters.addObject();
                filter.put("propertyName", "hs_lastmodifieddate");
                filter.put("operator", "GTE");
                filter.put("value", state.since.toString());
            }

            SaasHttpRequest req = SaasHttpRequest.builder()
                    .tenantId(state.tenantId)
                    .vendor(VENDOR)
                    .method("POST")
                    .url(baseUrl(state.config) + "/crm/v3/objects/" + state.streamName + "/search")
                    .bearer(state.accessToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(requestBody)
                    .build();
            try {
                JsonNode root = http.executeForJson(req,
                        SaasHttpClient.RetryPolicy.DEFAULT,
                        SaasHttpClient.RateLimit.HUBSPOT);
                for (JsonNode r : root.path("results")) {
                    buffer.add(toMap(r));
                }
                JsonNode next = root.path("paging").path("next");
                if (next.isMissingNode() || next.isNull() || !next.has("after")) {
                    exhausted = true;
                    state.afterCursor = null;
                } else {
                    state.afterCursor = next.path("after").asText(null);
                    if (state.afterCursor == null || state.afterCursor.isBlank()) {
                        exhausted = true;
                    }
                }
            } catch (SaasHttpException e) {
                log.warn("HubSpot {} page fetch failed: {}", state.streamName, e.getMessage());
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
            log.warn("Failed to map HubSpot record: {}", e.getMessage());
            return Map.of();
        }
    }

    private String baseUrl(SaasConnectorConfig config) {
        String b = config != null ? config.apiBaseUrl() : null;
        if (b == null || b.isBlank()) return DEFAULT_API_BASE;
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /** Test seam: ISO-8601 instant render used by {@link PageIterator}. */
    static final DateTimeFormatter SINCE_FORMAT = DateTimeFormatter.ISO_INSTANT;
}
