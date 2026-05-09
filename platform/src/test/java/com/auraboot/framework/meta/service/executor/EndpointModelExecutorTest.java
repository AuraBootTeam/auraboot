package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointModelExecutorTest {

    @Mock private MetaModelService metaModelService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private EndpointModelExecutor executor;

    EndpointModelExecutorTest() {
        // ObjectMapper is final, so use @InjectMocks via constructor pattern via reflection.
    }

    private EndpointModelExecutor newExecutor() {
        return new EndpointModelExecutor(metaModelService, objectMapper);
    }

    @Test
    void sourceType_is_endpoint() {
        assertThat(newExecutor().sourceType()).isEqualTo("endpoint");
    }

    // ---- validateEndpointUrl (static) -----------------------------

    @Test
    void validate_rejects_malformed_url() {
        assertThatThrownBy(() -> EndpointModelExecutor.validateEndpointUrl("ht!tp://%%%"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejects_non_http_scheme() {
        assertThatThrownBy(() -> EndpointModelExecutor.validateEndpointUrl("file:///etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("http/https");
    }

    @Test
    void validate_rejects_localhost_literal() {
        assertThatThrownBy(() -> EndpointModelExecutor.validateEndpointUrl("http://localhost/api"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not allowed");
    }

    @Test
    void validate_rejects_subdomain_localhost() {
        assertThatThrownBy(() -> EndpointModelExecutor.validateEndpointUrl("http://x.localhost/api"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not allowed");
    }

    @Test
    void validate_rejects_metadata_google_internal() {
        assertThatThrownBy(() -> EndpointModelExecutor.validateEndpointUrl("http://metadata.google.internal/"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejects_loopback_ip() {
        assertThatThrownBy(() -> EndpointModelExecutor.validateEndpointUrl("http://127.0.0.1/api"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("private/loopback");
    }

    @Test
    void validate_rejects_unresolvable_host() {
        assertThatThrownBy(() ->
            EndpointModelExecutor.validateEndpointUrl("http://this-host-must-not-exist-aura-test-12345.invalid/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unresolvable");
    }

    @Test
    void validate_rejects_missing_host() {
        assertThatThrownBy(() -> EndpointModelExecutor.validateEndpointUrl("http:///path"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- list/get config-failure paths ----------------------------

    @Test
    void list_throws_when_model_not_found() {
        when(metaModelService.getDefinitionByCode("m")).thenReturn(null);
        assertThatThrownBy(() -> newExecutor().list("m", null))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("Model definition not found");
    }

    @Test
    void list_throws_when_model_not_endpoint_sourceType() {
        ModelDefinition d = ModelDefinition.builder().code("m").sourceType("physical").build();
        when(metaModelService.getDefinitionByCode("m")).thenReturn(d);
        assertThatThrownBy(() -> newExecutor().list("m", null))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("non-endpoint");
    }

    @Test
    void list_throws_when_extension_missing() {
        ModelDefinition d = ModelDefinition.builder().code("m").sourceType("endpoint").build();
        when(metaModelService.getDefinitionByCode("m")).thenReturn(d);
        assertThatThrownBy(() -> newExecutor().list("m", null))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("missing extension.endpointAdapter");
    }

    @Test
    void list_throws_when_endpointAdapter_key_missing() {
        ModelDefinition d = ModelDefinition.builder().code("m").sourceType("endpoint")
            .extension(new HashMap<>()).build();
        when(metaModelService.getDefinitionByCode("m")).thenReturn(d);
        assertThatThrownBy(() -> newExecutor().list("m", null))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("missing extension.endpointAdapter");
    }

    @Test
    void list_throws_when_list_channel_missing() {
        Map<String, Object> ext = new HashMap<>();
        ext.put("endpointAdapter", Map.of()); // no list, no detail
        ModelDefinition d = ModelDefinition.builder().code("m").sourceType("endpoint").extension(ext).build();
        when(metaModelService.getDefinitionByCode("m")).thenReturn(d);
        assertThatThrownBy(() -> newExecutor().list("m", null))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("missing list channel");
    }

    @Test
    void list_throws_when_endpoint_resolves_loopback() {
        Map<String, Object> ext = new HashMap<>();
        ext.put("endpointAdapter", Map.of(
            "list", Map.of("endpoint", "http://127.0.0.1/api/items")));
        ModelDefinition d = ModelDefinition.builder().code("m").sourceType("endpoint").extension(ext).build();
        when(metaModelService.getDefinitionByCode("m")).thenReturn(d);
        assertThatThrownBy(() -> newExecutor().list("m", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void get_throws_when_detail_channel_missing() {
        Map<String, Object> ext = new HashMap<>();
        ext.put("endpointAdapter", Map.of("list", Map.of("endpoint", "https://example.com/")));
        ModelDefinition d = ModelDefinition.builder().code("m").sourceType("endpoint").extension(ext).build();
        when(metaModelService.getDefinitionByCode("m")).thenReturn(d);
        assertThatThrownBy(() -> newExecutor().get("m", "1"))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("missing detail channel");
    }

    @Test
    void get_throws_when_detail_endpoint_resolves_loopback() {
        Map<String, Object> ext = new HashMap<>();
        ext.put("endpointAdapter", Map.of(
            "detail", Map.of("endpoint", "http://127.0.0.1/api/items/{id}", "pathParams", java.util.List.of("id"))));
        ModelDefinition d = ModelDefinition.builder().code("m").sourceType("endpoint").extension(ext).build();
        when(metaModelService.getDefinitionByCode("m")).thenReturn(d);
        assertThatThrownBy(() -> newExecutor().get("m", "1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readAdapter_throws_on_malformed_config() {
        Map<String, Object> ext = new HashMap<>();
        ext.put("endpointAdapter", Map.of("list", "this-should-be-an-object"));
        ModelDefinition d = ModelDefinition.builder().code("m").sourceType("endpoint").extension(ext).build();
        when(metaModelService.getDefinitionByCode("m")).thenReturn(d);
        assertThatThrownBy(() -> newExecutor().list("m", null))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("malformed endpointAdapter");
    }
}
