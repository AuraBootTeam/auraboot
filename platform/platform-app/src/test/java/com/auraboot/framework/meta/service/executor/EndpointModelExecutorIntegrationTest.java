package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link EndpointModelExecutor}.
 *
 * <p>Post-P3-E (2026-04-18) the executor uses pinned-IP JDK HttpClient rather
 * than RestTemplate, so we can no longer stub calls via {@code
 * MockRestServiceServer}. The execution path is instead exercised via the
 * SSRF guard and validator contract:
 * <ul>
 *   <li>Registry wiring (sourceType=endpoint resolves to this executor).</li>
 *   <li>Static {@code validateEndpointUrl} rejects loopback / private / non-HTTP URLs.</li>
 *   <li>{@code list()} against a blocked endpoint URL surfaces the SSRF error
 *       (proving {@code validateEndpointUrl} runs before any socket attempt).</li>
 * </ul>
 *
 * <p>Happy-path HTTP success is covered end-to-end via the
 * {@code tests/e2e/bpm/designer-servicetask-http.spec.ts} Playwright spec and
 * the {@code HttpServiceTaskDelegateIntegrationTest} (which uses a real JDK
 * {@code HttpServer} against a non-loopback test-net address).
 */
@Slf4j
@DisplayName("EndpointModelExecutor Integration Test - P1-T9")
class EndpointModelExecutorIntegrationTest extends BaseIntegrationTest {

    @Autowired private ExecutorRegistry executorRegistry;
    @Autowired private MetaModelService metaModelService;
    @Autowired private EndpointModelExecutor endpointModelExecutor;

    @Test
    @DisplayName("executor is registered for sourceType=endpoint")
    void executorRegistered() {
        Optional<ModelDataExecutor> executor = executorRegistry.resolve("endpoint");
        assertThat(executor).isPresent();
        assertThat(executor.get()).isInstanceOf(EndpointModelExecutor.class);
    }

    @Test
    @DisplayName("list() on loopback endpoint is rejected by SSRF guard before any socket connect")
    void list_rejected_by_ssrf_guard_on_loopback_endpoint() {
        String modelCode = saveEndpointModel("epmx_ssrf_",
            Map.of(
                "list", Map.of(
                    "endpoint", "http://127.0.0.1/orders/page",
                    "method", "GET",
                    "responseItemsPath", "data.items",
                    "responseTotalPath", "data.total"
                )
            ));

        assertThatThrownBy(() -> endpointModelExecutor.list(modelCode,
                com.auraboot.framework.meta.dto.DynamicQueryRequest.builder()
                        .pageNum(1).pageSize(20).build()))
            .isInstanceOf(IllegalArgumentException.class);
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
