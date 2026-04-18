package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.bpm.converter.JsonToBpmnConverter;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test proving that {@link HttpServiceTaskDelegate} is wired into
 * real BPMN process execution: the designer JSON → BPMN XML conversion emits
 * the {@code smart:class=httpServiceTaskDelegate} attribute, SmartEngine
 * resolves the Spring bean, and the delegate performs a real HTTP call against
 * a JDK-provided {@link HttpServer} loopback endpoint.
 *
 * <p>The built-in {@link HttpServer} (jdk.httpserver) keeps the test
 * zero-dependency — no MockWebServer/WireMock on the classpath.
 */
@DisplayName("HttpServiceTaskDelegate integration tests (real BPMN + real SmartEngine)")
class HttpServiceTaskDelegateIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JsonToBpmnConverter jsonToBpmnConverter;

    @Autowired
    private SmartEngine smartEngine;

    private HttpServer server;
    private int port;
    private AtomicInteger hitCount;

    @BeforeEach
    void startLoopbackServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hitCount = new AtomicInteger();

        server.createContext("/ok", new JsonHandler(200, "{\"status\":\"UP\"}"));
        server.createContext("/boom", new JsonHandler(500, "{\"error\":\"boom\"}"));
        server.createContext("/slow", exchange -> {
            try {
                // Sleep longer than the BPMN-level timeoutMs to provoke a
                // read-timeout. The platform RestTemplate read timeout is
                // still the effective upper bound, so this test asserts the
                // failure path when the remote never returns in time.
                Thread.sleep(8_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            JsonHandler late = new JsonHandler(200, "{\"late\":true}");
            late.handle(exchange);
        });
        server.createContext("/echo-hit", exchange -> {
            hitCount.incrementAndGet();
            new JsonHandler(200, "{\"status\":\"UP\"}").handle(exchange);
        });

        server.setExecutor(null); // default executor — sufficient for tests
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopLoopbackServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // Designer JSON fixture: start → http-serviceTask → end
    // ------------------------------------------------------------------

    private Map<String, Object> httpProcessJson(String processKey,
                                                String url,
                                                String responseVar) {
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("id", "start_1");
        start.put("type", "startEvent");
        start.put("data", Map.of("label", "Start"));

        Map<String, Object> serviceConfig = new LinkedHashMap<>();
        serviceConfig.put("serviceType", "http");
        serviceConfig.put("serviceUrl", url);
        if (responseVar != null) {
            serviceConfig.put("responseVar", responseVar);
        }

        Map<String, Object> svcData = new LinkedHashMap<>();
        svcData.put("label", "Http Call");
        svcData.put("config", serviceConfig);

        Map<String, Object> svc = new LinkedHashMap<>();
        svc.put("id", "svc_http");
        svc.put("type", "serviceTask");
        svc.put("data", svcData);

        Map<String, Object> end = new LinkedHashMap<>();
        end.put("id", "end_1");
        end.put("type", "endEvent");
        end.put("data", Map.of("label", "End"));

        Map<String, Object> e1 = Map.of("id", "edge_1", "source", "start_1", "target", "svc_http");
        Map<String, Object> e2 = Map.of("id", "edge_2", "source", "svc_http", "target", "end_1");

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("key", processKey);
        json.put("name", "Http test process");
        json.put("nodes", List.of(start, svc, end));
        json.put("edges", List.of(e1, e2));
        return json;
    }

    private String deploy(Map<String, Object> json, String processKey) {
        String xml = jsonToBpmnConverter.convertFromMap(json);
        // Sanity: converter wires the HTTP delegate.
        assertThat(xml)
                .as("BPMN XML must reference the HTTP delegate bean")
                .contains("smart:class=\"" + BpmServiceTaskConstants.BEAN_HTTP_DELEGATE + "\"");
        assertThat(xml)
                .as("BPMN XML must carry the serviceUrl extension")
                .contains("smart:serviceUrl=");
        smartEngine.getRepositoryCommandService().deployWithUTF8Content(xml);
        return smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .filter(p -> processKey.equals(p.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "BPMN should be cached by SmartEngine, processKey=" + processKey))
                .getVersion();
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET success writes {status, body} into the responseVar process variable")
    void httpGet_success_writesResponseToProcessVariable() {
        String processKey = "it_http_ok_" + System.nanoTime();
        String url = "http://127.0.0.1:" + port + "/ok";
        String version = deploy(httpProcessJson(processKey, url, "healthResp"), processKey);

        Map<String, Object> vars = new HashMap<>();
        vars.put("sys_tenant_id", String.valueOf(getTestTenant().getId()));

        ProcessInstance instance = smartEngine.getProcessCommandService()
                .start(processKey, version, vars);

        assertThat(instance).isNotNull();
        assertThat(instance.getInstanceId()).isNotBlank();

        assertThat(vars)
                .as("HTTP delegate must write the response payload into process variables")
                .containsKey("healthResp");
        Object healthResp = vars.get("healthResp");
        assertThat(healthResp).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> respMap = (Map<String, Object>) healthResp;
        assertThat(respMap.get("status")).isEqualTo(200);
        assertThat(String.valueOf(respMap.get("body"))).contains("UP");
    }

    @Test
    @DisplayName("URL ${variable} substitution resolves against process variables")
    void httpGet_urlVariableSubstitution_resolvesFromProcessVars() {
        String processKey = "it_http_subst_" + System.nanoTime();
        // `endpoint` is supplied as a process variable; the URL template
        // references it as ${endpoint}.
        String urlTemplate = "http://127.0.0.1:" + port + "/${endpoint}";
        String version = deploy(httpProcessJson(processKey, urlTemplate, "resp"), processKey);

        Map<String, Object> vars = new HashMap<>();
        vars.put("sys_tenant_id", String.valueOf(getTestTenant().getId()));
        vars.put("endpoint", "echo-hit");

        smartEngine.getProcessCommandService().start(processKey, version, vars);

        assertThat(hitCount.get())
                .as("HTTP delegate must have hit /echo-hit after ${endpoint} substitution")
                .isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> respMap = (Map<String, Object>) vars.get("resp");
        assertThat(respMap).isNotNull();
        assertThat(respMap.get("status")).isEqualTo(200);
    }

    @Test
    @DisplayName("HTTP 500 propagates as task failure (no silent swallow)")
    void http5xx_propagatesFailure() {
        String processKey = "it_http_5xx_" + System.nanoTime();
        String url = "http://127.0.0.1:" + port + "/boom";
        String version = deploy(httpProcessJson(processKey, url, null), processKey);

        Map<String, Object> vars = new HashMap<>();
        vars.put("sys_tenant_id", String.valueOf(getTestTenant().getId()));

        assertThatThrownBy(() ->
                smartEngine.getProcessCommandService().start(processKey, version, vars))
                .as("5xx must bubble up — delegate must not silently absorb the failure")
                .hasMessageContaining(HttpServiceTaskDelegate.ERR_HTTP_CALL_FAILED);
    }

    @Test
    @DisplayName("Missing serviceUrl is rejected at BPMN conversion time")
    void missingServiceUrl_rejectedAtConversion() {
        Map<String, Object> json = httpProcessJson(
                "it_http_missing_" + System.nanoTime(), "", null);
        assertThatThrownBy(() -> jsonToBpmnConverter.convertFromMap(json))
                .hasMessageContaining("serviceUrl");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static final class JsonHandler implements HttpHandler {
        private final int status;
        private final String body;

        JsonHandler(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        }
    }
}
