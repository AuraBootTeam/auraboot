package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Integration test for {@link EndpointModelExecutor} (P1-T9).
 *
 * <p>Uses Spring's {@link MockRestServiceServer} to stub the shared
 * {@link RestTemplate} bean so no real HTTP sockets are opened. Exercises:
 * <ul>
 *     <li>list() extracting items/total from configured JSON paths</li>
 *     <li>get() replacing {pathParam} placeholder and extracting the single item</li>
 *     <li>SSRF guard rejecting loopback URLs ({@code 127.0.0.1})</li>
 * </ul>
 */
@Slf4j
@DisplayName("EndpointModelExecutor Integration Test - P1-T9")
class EndpointModelExecutorIntegrationTest extends BaseIntegrationTest {

    @Autowired private ExecutorRegistry executorRegistry;
    @Autowired private MetaModelService metaModelService;
    @Autowired private RestTemplate restTemplate;
    @Autowired private EndpointModelExecutor endpointModelExecutor;

    private MockRestServiceServer mockServer;

    @BeforeEach
    void resetMockServer() {
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    @DisplayName("executor is registered for sourceType=endpoint")
    void executorRegistered() {
        Optional<ModelDataExecutor> executor = executorRegistry.resolve("endpoint");
        assertThat(executor).isPresent();
        assertThat(executor.get()).isInstanceOf(EndpointModelExecutor.class);
    }

    @Test
    @DisplayName("list() extracts items + total from configured response paths")
    void list_extracts_items_and_total() {
        String modelCode = saveEndpointModel("epmx_list_",
            Map.of(
                "list", Map.of(
                    "endpoint", "https://8.8.8.8/orders/page",
                    "method", "GET",
                    "responseItemsPath", "data.items",
                    "responseTotalPath", "data.total",
                    "pageParam", "pageNum",
                    "pageSizeParam", "pageSize"
                )
            ));

        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("8.8.8.8/orders/page")))
            .andExpect(method(GET))
            .andRespond(withSuccess(
                "{\"data\":{\"items\":[{\"id\":\"o1\",\"amount\":100},{\"id\":\"o2\",\"amount\":200}],\"total\":42}}",
                MediaType.APPLICATION_JSON));

        PaginationResult<Map<String, Object>> result = endpointModelExecutor.list(
            modelCode,
            DynamicQueryRequest.builder().pageNum(1).pageSize(20).build());

        mockServer.verify();
        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isEqualTo(42L);
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords().get(0).get("id")).isEqualTo("o1");
        assertThat(result.getRecords().get(0).get("amount")).isEqualTo(100);
        assertThat(result.getRecords().get(1).get("id")).isEqualTo("o2");
    }

    @Test
    @DisplayName("get() replaces {id} path param and extracts item by responseItemPath")
    void get_replaces_path_param_and_extracts_item() {
        String modelCode = saveEndpointModel("epmx_get_",
            Map.of(
                "list", Map.of(
                    "endpoint", "https://8.8.8.8/orders/page"
                ),
                "detail", Map.of(
                    "endpoint", "https://8.8.8.8/orders/{id}",
                    "method", "GET",
                    "responseItemPath", "data",
                    "pathParams", List.of("id")
                )
            ));

        mockServer.expect(requestTo("https://8.8.8.8/orders/o-42"))
            .andExpect(method(GET))
            .andRespond(withSuccess(
                "{\"data\":{\"id\":\"o-42\",\"customer\":\"acme\",\"amount\":999}}",
                MediaType.APPLICATION_JSON));

        Map<String, Object> row = endpointModelExecutor.get(modelCode, "o-42");

        mockServer.verify();
        assertThat(row).isNotNull();
        assertThat(row.get("id")).isEqualTo("o-42");
        assertThat(row.get("customer")).isEqualTo("acme");
        assertThat(row.get("amount")).isEqualTo(999);
    }

    @Test
    @DisplayName("SSRF guard rejects loopback hosts")
    void ssrf_guard_rejects_loopback() {
        assertThatThrownBy(() -> EndpointModelExecutor.validateEndpointUrl("http://127.0.0.1/api/x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("private");

        assertThatThrownBy(() -> EndpointModelExecutor.validateEndpointUrl("http://localhost/api/x"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> EndpointModelExecutor.validateEndpointUrl("ftp://example.com/x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("http");

        // 10.0.0.0/8 private range
        assertThatThrownBy(() -> EndpointModelExecutor.validateEndpointUrl("http://10.0.0.5/api"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Save an endpoint virtual model with the given adapter config nested under
     * {@code extension.endpointAdapter}.
     */
    private String saveEndpointModel(String prefix, Map<String, Object> adapterConfig) {
        String modelCode = prefix + System.currentTimeMillis() + "_" + Math.abs(System.nanoTime() % 10000);
        Map<String, Object> extension = new HashMap<>();
        extension.put("endpointAdapter", adapterConfig);

        ModelDefinition def = ModelDefinition.builder()
            .code(modelCode)
            .displayName("Endpoint Model " + modelCode)
            .modelType("virtual")
            .sourceType("endpoint")
            .sourceRef("orders-api")
            .primaryKey("id")
            .capabilities(ModelCapabilities.virtualReadOnly().toBuilder()
                .detailKeyField("id")
                .build())
            .fields(List.of(
                FieldDefinition.builder()
                    .code("id").name("id").displayName("id")
                    .dataType("string").columnName("id")
                    .primaryKey(true)
                    .build()))
            .status("published")
            .extension(extension)
            .build();
        ModelDefinition saved = metaModelService.saveDefinition(def);
        assertThat(saved.getSourceType()).isEqualTo("endpoint");
        assertThat(saved.getExtension()).isNotNull();
        assertThat(saved.getExtension().get("endpointAdapter")).isNotNull();
        return modelCode;
    }
}
