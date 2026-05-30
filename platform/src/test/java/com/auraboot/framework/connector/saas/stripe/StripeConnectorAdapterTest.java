package com.auraboot.framework.connector.saas.stripe;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.connector.saas.ReadCursor;
import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.saas.http.SaasHttpClient;
import com.auraboot.framework.connector.saas.http.SaasHttpException;
import com.auraboot.framework.connector.saas.http.SaasHttpRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeConnectorAdapterTest {

    private SaasHttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private StripeConnectorAdapter adapter;

    @BeforeEach
    void setup() {
        http = mock(SaasHttpClient.class);
        adapter = new StripeConnectorAdapter(http, json);
        MetaContext.setCurrentTenantId(1L);
        MetaContext.setCurrentUserId(100L);
    }

    @AfterEach
    void teardown() { MetaContext.clear(); }

    private SaasConnectorConfig config() {
        return new SaasConnectorConfig("saas-stripe", "apikey",
                "pk_test_publishable", "sk_test_secret",
                null, List.of(),
                "https://api.stripe.com", null, Map.of());
    }

    private static JsonNode jsonOf(String s) {
        try { return new ObjectMapper().readTree(s); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    // -- descriptor + discover ----------------------------------------------

    @Test
    void descriptorListsEightStreams() {
        assertThat(adapter.descriptor().protocolType()).isEqualTo("saas-stripe");
        assertThat(adapter.descriptor().supportedEndpointCodes())
                .containsExactly("customers", "charges", "invoices", "subscriptions",
                                 "products", "prices", "payment_intents", "events");
    }

    @Test
    void discoverIsStaticAndExposesUniformMetadata() {
        Map<String, Object> streams = adapter.discover(config());
        assertThat(streams).hasSize(8);
        for (Map.Entry<String, Object> e : streams.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) e.getValue();
            assertThat(meta).containsEntry("primaryKey", "id");
            assertThat(meta).containsEntry("cursorField", "created");
            assertThat(meta).containsEntry("supportsIncremental", true);
            assertThat(meta).containsEntry("source", "core");
        }
    }

    // -- read ---------------------------------------------------------------

    @Test
    void readHappyPathPaginatesViaStartingAfter() {
        when(http.executeForJson(any(), any(), any()))
                .thenReturn(jsonOf("""
                    {"data":[{"id":"cus_1","name":"A"},{"id":"cus_2","name":"B"}],
                     "has_more":true}
                    """))
                .thenReturn(jsonOf("""
                    {"data":[{"id":"cus_3","name":"C"}],"has_more":false}
                    """));

        List<Map<String, Object>> rows = adapter.read(config(), "customers",
                ReadCursor.empty()).toList();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("id", "cus_1");
        assertThat(rows.get(2)).containsEntry("id", "cus_3");
        verify(http, times(2)).executeForJson(any(), any(), any());
    }

    @Test
    void readSendsBearerAndStripeVersion() {
        when(http.executeForJson(any(), any(), any()))
                .thenReturn(jsonOf("{\"data\":[],\"has_more\":false}"));
        adapter.read(config(), "customers", ReadCursor.empty()).toList();
        ArgumentCaptor<SaasHttpRequest> cap = ArgumentCaptor.forClass(SaasHttpRequest.class);
        verify(http).executeForJson(cap.capture(), any(), any());
        SaasHttpRequest req = cap.getValue();
        assertThat(req.method()).isEqualTo("GET");
        assertThat(req.headers())
                .containsEntry("Authorization", "Bearer sk_test_secret")
                .containsEntry("Stripe-Version", "2024-06-20");
    }

    @Test
    void readUrlIncludesLimitAndStartingAfterAndCreatedGte() {
        when(http.executeForJson(any(), any(), any()))
                .thenReturn(jsonOf("{\"data\":[],\"has_more\":false}"));
        Instant since = Instant.parse("2026-01-01T00:00:00Z"); // 1767225600
        adapter.read(config(), "charges",
                new ReadCursor(since, "cus_AAA", Map.of())).toList();
        ArgumentCaptor<SaasHttpRequest> cap = ArgumentCaptor.forClass(SaasHttpRequest.class);
        verify(http).executeForJson(cap.capture(), any(), any());
        String url = cap.getValue().url();
        assertThat(url)
                .startsWith("https://api.stripe.com/v1/charges?limit=100")
                .contains("starting_after=cus_AAA")
                .contains("created%5Bgte%5D=" + since.getEpochSecond());
    }

    @Test
    void readFeedsLastIdIntoNextPageCursor() {
        when(http.executeForJson(any(), any(), any()))
                .thenReturn(jsonOf("""
                    {"data":[{"id":"ch_1"},{"id":"ch_2"}],"has_more":true}
                    """))
                .thenReturn(jsonOf("{\"data\":[],\"has_more\":false}"));
        adapter.read(config(), "charges", ReadCursor.empty()).toList();
        ArgumentCaptor<SaasHttpRequest> cap = ArgumentCaptor.forClass(SaasHttpRequest.class);
        verify(http, times(2)).executeForJson(cap.capture(), any(), any());
        // 2nd call URL should carry the last id from page 1.
        assertThat(cap.getAllValues().get(1).url()).contains("starting_after=ch_2");
    }

    @Test
    void readHasMoreFalseStopsPaginating() {
        when(http.executeForJson(any(), any(), any()))
                .thenReturn(jsonOf("""
                    {"data":[{"id":"sub_1"}],"has_more":false}
                    """));
        adapter.read(config(), "subscriptions", ReadCursor.empty()).toList();
        verify(http, times(1)).executeForJson(any(), any(), any());
    }

    @Test
    void readLazinessFetchesOnePageBeforeFirstAdvance() {
        when(http.executeForJson(any(), any(), any()))
                .thenReturn(jsonOf("""
                    {"data":[{"id":"a"},{"id":"b"}],"has_more":true}
                    """))
                .thenReturn(jsonOf("{\"data\":[{\"id\":\"c\"}],\"has_more\":false}"));
        Stream<Map<String, Object>> stream = adapter.read(config(), "events",
                ReadCursor.empty());
        List<Map<String, Object>> first2 = stream.limit(2).toList();
        assertThat(first2).hasSize(2);
        verify(http, times(1)).executeForJson(any(), any(), any());
    }

    @Test
    void readEmptyResultsStops() {
        when(http.executeForJson(any(), any(), any()))
                .thenReturn(jsonOf("{\"data\":[],\"has_more\":false}"));
        assertThat(adapter.read(config(), "products", ReadCursor.empty()).toList())
                .isEmpty();
    }

    @Test
    void readMidStreamTransportFailureReturnsBuffered() {
        when(http.executeForJson(any(), any(), any()))
                .thenReturn(jsonOf("""
                    {"data":[{"id":"in_1"}],"has_more":true}
                    """))
                .thenThrow(new SaasHttpException("502 bad gateway", 502));
        List<Map<String, Object>> rows = adapter.read(config(), "invoices",
                ReadCursor.empty()).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("id", "in_1");
    }

    @Test
    void readUnknownStreamThrows() {
        assertThatThrownBy(() -> adapter.read(config(), "users", ReadCursor.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Stripe stream");
    }

    @Test
    void readBlankStreamThrows() {
        assertThatThrownBy(() -> adapter.read(config(), "  ", ReadCursor.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.read(config(), null, ReadCursor.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readMissingApiKeyThrows() {
        SaasConnectorConfig blankKey = new SaasConnectorConfig("saas-stripe", "apikey",
                "cid", "", null, List.of(),
                "https://api.stripe.com", null, Map.of());
        assertThatThrownBy(() -> adapter.read(blankKey, "customers", ReadCursor.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientSecret");
    }

    @Test
    void readBaseUrlOverrideRespectedWithTrailingSlashStripped() {
        SaasConnectorConfig override = new SaasConnectorConfig("saas-stripe", "apikey",
                "cid", "sk_test", null, List.of(),
                "https://example.test/", null, Map.of());
        when(http.executeForJson(any(), any(), any()))
                .thenReturn(jsonOf("{\"data\":[],\"has_more\":false}"));
        adapter.read(override, "customers", ReadCursor.empty()).toList();
        ArgumentCaptor<SaasHttpRequest> cap = ArgumentCaptor.forClass(SaasHttpRequest.class);
        verify(http).executeForJson(cap.capture(), any(), any());
        assertThat(cap.getValue().url())
                .startsWith("https://example.test/v1/customers?limit=100");
    }

    @Test
    void readHasMoreTrueButNoIdEndsStream() {
        // Defensive: malformed response (has_more=true but no id field on any
        // record) — stop iterating to avoid an infinite loop.
        when(http.executeForJson(any(), any(), any()))
                .thenReturn(jsonOf("""
                    {"data":[{"object":"customer"}],"has_more":true}
                    """));
        List<Map<String, Object>> rows = adapter.read(config(), "customers",
                ReadCursor.empty()).toList();
        assertThat(rows).hasSize(1);
        verify(http, times(1)).executeForJson(any(), any(), any());
    }
}
