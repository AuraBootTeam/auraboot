package com.auraboot.framework.connector.saas.hubspot;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.connector.saas.ReadCursor;
import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.saas.http.SaasHttpClient;
import com.auraboot.framework.connector.saas.http.SaasHttpException;
import com.auraboot.framework.connector.saas.http.SaasHttpRequest;
import com.auraboot.framework.connector.saas.oauth.OAuth2TokenStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HubspotConnectorAdapterTest {

    private SaasHttpClient http;
    private OAuth2TokenStore tokenStore;
    private final ObjectMapper json = new ObjectMapper();
    private HubspotConnectorAdapter adapter;
    private final Deque<JsonNode> scriptedJson = new ArrayDeque<>();
    private final Deque<SaasHttpException> scriptedErrors = new ArrayDeque<>();

    @BeforeEach
    void setup() {
        http = mock(SaasHttpClient.class);
        tokenStore = mock(OAuth2TokenStore.class);
        adapter = new HubspotConnectorAdapter(http, tokenStore, json);
        when(http.executeForJson(any(), any(), any())).thenAnswer(inv -> {
            if (!scriptedErrors.isEmpty()) throw scriptedErrors.pop();
            if (scriptedJson.isEmpty()) {
                throw new AssertionError("Unexpected extra HTTP call");
            }
            return scriptedJson.pop();
        });
        when(tokenStore.getValidAccessToken(any(), any())).thenReturn("bearer-token");
        MetaContext.setCurrentTenantId(1L);
        MetaContext.setCurrentUserId(100L);
    }

    @AfterEach
    void teardown() {
        MetaContext.clear();
    }

    private static SaasConnectorConfig config() {
        return new SaasConnectorConfig("saas-hubspot", "oauth2",
                "cid", "csec", "rt", List.of("crm.objects.contacts.read"),
                "https://api.hubapi.com", null, Map.of());
    }

    private static JsonNode jsonOf(String s) {
        try { return new ObjectMapper().readTree(s); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    // -- descriptor + discover --------------------------------------------

    @Test
    void descriptorExposesSixCoreStreams() {
        assertThat(adapter.descriptor().protocolType()).isEqualTo("saas-hubspot");
        assertThat(adapter.descriptor().supportedEndpointCodes())
                .containsExactly("companies", "contacts", "deals",
                                 "tickets", "line_items", "products");
    }

    @Test
    void discoverIncludesCoreAndCustomObjects() {
        scriptedJson.push(jsonOf("""
            {"results":[
               {"name":"projects","objectTypeId":"2-12345"},
               {"name":"invoices","objectTypeId":"2-67890"}
            ]}
            """));

        Map<String, Object> streams = adapter.discover(config());

        assertThat(streams).hasSize(8);
        assertThat(streams.keySet()).contains("contacts", "deals", "projects", "invoices");
        @SuppressWarnings("unchecked")
        Map<String, Object> contactsMeta = (Map<String, Object>) streams.get("contacts");
        assertThat(contactsMeta).containsEntry("source", "core");
        assertThat(contactsMeta).containsEntry("primaryKey", "id");
        assertThat(contactsMeta).containsEntry("cursorField", "hs_lastmodifieddate");
        assertThat(contactsMeta).containsEntry("supportsIncremental", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> projectsMeta = (Map<String, Object>) streams.get("projects");
        assertThat(projectsMeta).containsEntry("source", "custom");
    }

    @Test
    void discoverDegradesGracefullyWhenCustomSchemaCallFails() {
        scriptedErrors.push(new SaasHttpException("schemas 403", 403));

        Map<String, Object> streams = adapter.discover(config());

        // Core streams still present.
        assertThat(streams.keySet()).hasSize(6);
        assertThat(streams.keySet()).contains("contacts", "deals", "tickets");
    }

    // -- read -----------------------------------------------------------

    @Test
    void readReturnsRecordsAcrossPagesUsingAfterCursor() {
        scriptedJson.push(jsonOf("""
            {"results":[{"id":"3","properties":{"name":"C"}}],
             "paging":{}}
            """));
        scriptedJson.push(jsonOf("""
            {"results":[{"id":"1","properties":{"name":"A"}},
                        {"id":"2","properties":{"name":"B"}}],
             "paging":{"next":{"after":"PAGE-2"}}}
            """));

        List<Map<String, Object>> rows = adapter.read(config(), "contacts",
                ReadCursor.empty()).toList();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("id", "1");
        assertThat(rows.get(2)).containsEntry("id", "3");
        verify(http, times(2)).executeForJson(any(), any(), any());
    }

    @Test
    void readSendsSinceFilterWhenCursorHasSince() {
        scriptedJson.push(jsonOf("""
            {"results":[{"id":"1"}],"paging":{}}
            """));
        Instant since = Instant.parse("2026-01-01T00:00:00Z");
        adapter.read(config(), "contacts",
                new ReadCursor(since, null, Map.of())).toList();

        ArgumentCaptor<SaasHttpRequest> cap = ArgumentCaptor.forClass(SaasHttpRequest.class);
        verify(http).executeForJson(cap.capture(), any(), any());
        SaasHttpRequest req = cap.getValue();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.url()).isEqualTo("https://api.hubapi.com/crm/v3/objects/contacts/search");
        assertThat(req.headers()).containsEntry("Authorization", "Bearer bearer-token");
        String bodyJson = json.valueToTree(req.body()).toString();
        assertThat(bodyJson)
                .contains("\"propertyName\":\"hs_lastmodifieddate\"")
                .contains("\"operator\":\"GTE\"")
                .contains("2026-01-01T00:00:00Z")
                .contains("\"limit\":100");
    }

    @Test
    void readResumesFromAfterCursor() {
        scriptedJson.push(jsonOf("""
            {"results":[{"id":"7"}],"paging":{}}
            """));
        adapter.read(config(), "deals",
                new ReadCursor(null, "RESUME-AT", Map.of())).toList();
        ArgumentCaptor<SaasHttpRequest> cap = ArgumentCaptor.forClass(SaasHttpRequest.class);
        verify(http).executeForJson(cap.capture(), any(), any());
        String bodyJson = json.valueToTree(cap.getValue().body()).toString();
        assertThat(bodyJson).contains("\"after\":\"RESUME-AT\"");
    }

    @Test
    void readWithoutSinceOmitsFilterGroups() {
        scriptedJson.push(jsonOf("""
            {"results":[],"paging":{}}
            """));
        adapter.read(config(), "contacts", ReadCursor.empty()).toList();
        ArgumentCaptor<SaasHttpRequest> cap = ArgumentCaptor.forClass(SaasHttpRequest.class);
        verify(http).executeForJson(cap.capture(), any(), any());
        String bodyJson = json.valueToTree(cap.getValue().body()).toString();
        assertThat(bodyJson).doesNotContain("filterGroups");
        // Sorts still emitted.
        assertThat(bodyJson).contains("\"sorts\"").contains("ASCENDING");
    }

    @Test
    void readLaziness() {
        // Two pages scripted. Iterator should only fetch the first until the
        // consumer advances past it.
        scriptedJson.push(jsonOf("""
            {"results":[{"id":"3"}],"paging":{}}
            """));
        scriptedJson.push(jsonOf("""
            {"results":[{"id":"1"},{"id":"2"}],
             "paging":{"next":{"after":"PAGE-2"}}}
            """));

        Stream<Map<String, Object>> stream = adapter.read(config(), "contacts",
                ReadCursor.empty());
        // Consume only the first two records → only 1 page fetched.
        List<Map<String, Object>> first2 = stream.limit(2).toList();
        assertThat(first2).hasSize(2);
        verify(http, times(1)).executeForJson(any(), any(), any());
    }

    @Test
    void readHandlesEmptyResultsPage() {
        scriptedJson.push(jsonOf("""
            {"results":[],"paging":{}}
            """));
        List<Map<String, Object>> rows = adapter.read(config(), "contacts",
                ReadCursor.empty()).toList();
        assertThat(rows).isEmpty();
    }

    @Test
    void readShortCircuitsOnInvalidStream() {
        assertThatThrownBy(() -> adapter.read(config(), null, ReadCursor.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.read(config(), "  ", ReadCursor.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readSurfacesNoExceptionOnTransportFailureMidStream() {
        // First page OK, second page fails → stream returns the records it
        // already buffered and stops. Direct stubbing (not the scripted
        // queue) because we want a precise call-1 vs call-2 ordering.
        org.mockito.Mockito.reset(http);
        when(http.executeForJson(any(), any(), any()))
                .thenReturn(jsonOf("""
                    {"results":[{"id":"1"}],
                     "paging":{"next":{"after":"PAGE-2"}}}
                    """))
                .thenThrow(new SaasHttpException("503 service unavailable", 503));
        List<Map<String, Object>> rows = adapter.read(config(), "contacts",
                ReadCursor.empty()).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("id", "1");
    }

    @Test
    void readSendsExactBearerFromTokenStore() {
        scriptedJson.push(jsonOf("""
            {"results":[{"id":"1"}],"paging":{}}
            """));
        adapter.read(config(), "contacts", ReadCursor.empty()).toList();
        verify(tokenStore).getValidAccessToken(anyLong(), any());
        ArgumentCaptor<SaasHttpRequest> cap = ArgumentCaptor.forClass(SaasHttpRequest.class);
        verify(http).executeForJson(cap.capture(), any(), any());
        assertThat(cap.getValue().headers())
                .containsEntry("Authorization", "Bearer bearer-token");
    }

    @Test
    void apiBaseUrlOverrideRespected() {
        SaasConnectorConfig override = new SaasConnectorConfig("saas-hubspot", "oauth2",
                "cid", "csec", "rt", List.of(),
                "https://eu1.api.hubapi.com/", null, Map.of());
        scriptedJson.push(jsonOf("""
            {"results":[{"id":"1"}],"paging":{}}
            """));
        adapter.read(override, "contacts", ReadCursor.empty()).toList();
        ArgumentCaptor<SaasHttpRequest> cap = ArgumentCaptor.forClass(SaasHttpRequest.class);
        verify(http).executeForJson(cap.capture(), any(), any());
        // Trailing slash stripped.
        assertThat(cap.getValue().url())
                .isEqualTo("https://eu1.api.hubapi.com/crm/v3/objects/contacts/search");
    }
}
