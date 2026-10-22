package com.auraboot.framework.agent.provider;

import com.auraboot.framework.common.util.SsrfValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-wire protocol tests for the official Java SDK integration.
 *
 * <p>The in-process server requires initialize → initialized → operation and a
 * reusable {@code Mcp-Session-Id}; a client that skips negotiation cannot make
 * these tests pass.
 */
class McpClientProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_ID = "session-protocol-test";

    private HttpServer server;
    private ExecutorService serverExecutor;
    private OutputStream legacySseOutput;
    private String baseUrl;
    private final List<McpClient> openClients = new ArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, String>> headersByMethod =
            new ConcurrentHashMap<>();
    private final Map<String, String> bodiesByMethod = new ConcurrentHashMap<>();
    private volatile String toolsResult = "{\"tools\":[]}";
    private volatile Map<String, String> pagedToolsResults = Map.of();
    private volatile String callResult = "{\"content\":[]}";
    private volatile String errorMethod;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", this::handle);
        server.createContext("/sse", this::handleLegacySse);
        server.createContext("/message", this::handleLegacyMessage);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stopServer() {
        openClients.forEach(McpClient::close);
        if (legacySseOutput != null) {
            try {
                legacySseOutput.close();
            } catch (IOException ignored) {
                // Test fixture teardown.
            }
        }
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    private void handleLegacySse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        legacySseOutput = exchange.getResponseBody();
        writeLegacySseEvent("endpoint", "/message");
    }

    private void handleLegacyMessage(HttpExchange exchange) throws IOException {
        String body = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode request = MAPPER.readTree(body);
        String method = request.path("method").asText("");
        methods.add(method);
        bodiesByMethod.put(method, body);
        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach(
                (key, values) -> headers.put(
                        key.toLowerCase(), String.join(", ", values)));
        headersByMethod.put(method, headers);
        exchange.sendResponseHeaders(202, -1);
        exchange.close();

        if ("notifications/initialized".equals(method)) {
            return;
        }
        String id = request.path("id").toString();
        String response = switch (method) {
            case "initialize" -> initializeResponse(id, request);
            case "tools/list" -> success(id, toolsResult);
            case "tools/call" -> success(id, callResult);
            default -> "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";
        };
        writeLegacySseEvent("message", response);
    }

    private synchronized void writeLegacySseEvent(String event, String data)
            throws IOException {
        if (legacySseOutput == null) {
            throw new IOException("legacy SSE stream is not connected");
        }
        legacySseOutput.write(
                ("event: " + event + "\ndata: " + data + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
        legacySseOutput.flush();
    }

    private void handle(HttpExchange exchange) throws IOException {
        if ("DELETE".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }

        String body = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode request = MAPPER.readTree(body);
        String method = request.path("method").asText("");
        methods.add(method);
        bodiesByMethod.put(method, body);
        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach(
                (key, values) -> headers.put(
                        key.toLowerCase(), String.join(", ", values)));
        headersByMethod.put(method, headers);

        if ("notifications/initialized".equals(method)) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }

        String id = request.path("id").toString();
        String response;
        if (method.equals(errorMethod)) {
            response = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";
        } else {
            response = switch (method) {
                case "initialize" -> initializeResponse(id, request);
                case "tools/list" -> success(id, pagedToolsResult(request));
                case "tools/call" -> success(id, callResult);
                default -> "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";
            };
        }

        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if ("initialize".equals(method)) {
            exchange.getResponseHeaders().add("Mcp-Session-Id", SESSION_ID);
        } else if (!SESSION_ID.equals(headers.get("mcp-session-id"))) {
            byte[] missing = "missing session".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, missing.length);
            exchange.getResponseBody().write(missing);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }

    private static String initializeResponse(String id, JsonNode request) {
        String version = request.path("params").path("protocolVersion")
                .asText("2025-06-18");
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{"
                + "\"protocolVersion\":\"" + version + "\","
                + "\"capabilities\":{\"tools\":{\"listChanged\":false}},"
                + "\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1.0\"}}}";
    }

    private static String success(String id, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
    }

    private String pagedToolsResult(JsonNode request) {
        String cursor = request.path("params").path("cursor").asText("");
        return pagedToolsResults.getOrDefault(cursor, toolsResult);
    }

    private McpClient client() {
        McpClient client = new McpClient(
                MAPPER, new McpStdioCommandPolicy("node")) {
            @Override
            protected SsrfValidator.ValidatedTarget validateTarget(String serverUrl) {
                URI uri = URI.create(serverUrl);
                try {
                    return new SsrfValidator.ValidatedTarget(
                            uri, uri.getHost(), InetAddress.getByName("127.0.0.1"),
                            uri.getPort(), uri.getScheme());
                } catch (UnknownHostException error) {
                    throw new IllegalStateException(error);
                }
            }
        };
        openClients.add(client);
        return client;
    }

    private static McpServerTarget httpTarget(String url) {
        return new McpServerTarget(
                1L, "server-pid", "test-server", url,
                "streamable_http", null, null, List.of(), Map.of());
    }

    @Test
    @DisplayName("Streamable HTTP initializes before tools/list and reuses the negotiated session")
    void listTools_negotiatesAndReusesSession() {
        client().listTools(httpTarget(baseUrl));

        assertThat(methods).containsSubsequence(
                "initialize", "notifications/initialized", "tools/list");
        assertThat(headersByMethod.get("tools/list").get("mcp-session-id"))
                .isEqualTo(SESSION_ID);
        assertThat(headersByMethod.get("tools/list").get("accept"))
                .contains("application/json")
                .contains("text/event-stream");
    }

    @Test
    @DisplayName("registered bearer credentials reach initialize and operation headers only")
    void bearerAuth_isSentOnWireButNeverInBody() {
        String secret = "ghp_secret";
        McpServerTarget target = new McpServerTarget(
                1L, "auth-pid", "github", baseUrl, "streamable_http",
                "bearer", Map.of("token", secret), List.of(), Map.of());

        client().listTools(target);

        assertThat(headersByMethod.get("initialize").get("authorization"))
                .isEqualTo("Bearer " + secret);
        assertThat(headersByMethod.get("tools/list").get("authorization"))
                .isEqualTo("Bearer " + secret);
        assertThat(bodiesByMethod.values()).allSatisfy(body ->
                assertThat(body).doesNotContain(secret));
    }

    @Test
    @DisplayName("legacy SSE transport initializes and discovers tools over the event stream")
    void legacySse_initializesAndListsTools() {
        toolsResult = "{\"tools\":[{\"name\":\"legacy-search\","
                + "\"inputSchema\":{\"type\":\"object\"}}]}";
        String sseUrl =
                "http://localhost:" + server.getAddress().getPort() + "/sse";
        McpServerTarget target = new McpServerTarget(
                1L, "legacy-sse-pid", "legacy-sse", sseUrl,
                "sse", "bearer", Map.of("token", "sse-secret"),
                List.of(), Map.of());

        List<McpClient.McpToolInfo> tools = client().listTools(target);

        assertThat(tools).extracting(McpClient.McpToolInfo::getName)
                .containsExactly("legacy-search");
        assertThat(methods).containsSubsequence(
                "initialize", "notifications/initialized", "tools/list");
        assertThat(headersByMethod.get("initialize").get("authorization"))
                .isEqualTo("Bearer sse-secret");
        assertThat(headersByMethod.get("tools/list").get("authorization"))
                .isEqualTo("Bearer sse-secret");
    }

    @Test
    @DisplayName("API-key auth honors a configured header name")
    void apiKeyAuth_usesConfiguredHeaderName() {
        McpServerTarget target = new McpServerTarget(
                1L, "key-pid", "vendor", baseUrl, "streamable_http",
                "api_key", Map.of("header", "X-Vendor-Key", "token", "k-123"),
                List.of(), Map.of());

        client().listTools(target);

        assertThat(headersByMethod.get("tools/list").get("x-vendor-key"))
                .isEqualTo("k-123");
    }

    @Test
    @DisplayName("tools/call is sent through the initialized session")
    void callTool_usesInitializedSession() {
        callResult = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}";

        Map<String, Object> result =
                client().callTool(httpTarget(baseUrl), "search", Map.of("q", "term"));

        assertThat(result).containsKey("content");
        assertThat(methods).containsSubsequence("initialize", "tools/call");
        assertThat(headersByMethod.get("tools/call").get("mcp-session-id"))
                .isEqualTo(SESSION_ID);
        assertThat(bodiesByMethod.get("tools/call"))
                .contains("\"name\":\"search\"")
                .contains("\"q\":\"term\"");
    }

    @Test
    @DisplayName("tools/call isError is surfaced as a failure")
    void callTool_isErrorTrue_raises() {
        callResult = "{\"isError\":true,\"content\":["
                + "{\"type\":\"text\",\"text\":\"repo not found\"}]}";

        assertThatThrownBy(() ->
                client().callTool(httpTarget(baseUrl), "search", Map.of()))
                .isInstanceOf(McpClient.McpClientException.class)
                .hasMessageContaining("repo not found");
    }

    @Test
    @DisplayName("tools/list definitions are mapped from the SDK result")
    void listTools_parsesTools() {
        toolsResult = "{\"tools\":[{\"name\":\"search\","
                + "\"description\":\"Search things\","
                + "\"inputSchema\":{\"type\":\"object\"}}]}";

        List<McpClient.McpToolInfo> tools =
                client().listTools(httpTarget(baseUrl));

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getName()).isEqualTo("search");
        assertThat(tools.get(0).getDescription()).isEqualTo("Search things");
        assertThat(tools.get(0).getInputSchema())
                .containsEntry("type", "object");
    }

    @Test
    @DisplayName("tools/list follows pagination cursors until all tools are discovered")
    void listTools_followsPagination() {
        pagedToolsResults = Map.of(
                "", "{\"tools\":[{\"name\":\"first\",\"inputSchema\":{}}],"
                        + "\"nextCursor\":\"page-2\"}",
                "page-2", "{\"tools\":[{\"name\":\"second\",\"inputSchema\":{}}]}");

        List<McpClient.McpToolInfo> tools =
                client().listTools(httpTarget(baseUrl));

        assertThat(tools).extracting(McpClient.McpToolInfo::getName)
                .containsExactly("first", "second");
        // Schema caching may perform an initial discovery during client
        // initialization; the cursor-bearing request is the pagination proof.
        assertThat(methods.stream().filter("tools/list"::equals))
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(bodiesByMethod.get("tools/list")).contains("\"cursor\":\"page-2\"");
    }

    @Test
    @DisplayName("JSON-RPC errors are exposed with the remote message")
    void jsonRpcError_raises() {
        errorMethod = "tools/list";

        assertThatThrownBy(() -> client().listTools(httpTarget(baseUrl)))
                .isInstanceOf(McpClient.McpClientException.class)
                .hasMessageContaining("Method not found");
    }

    @Test
    @DisplayName("stdio launches an allowlisted executable with args and encrypted env material")
    void stdio_roundTripOverRealChildProcess() throws Exception {
        Path fixture = Path.of(
                getClass().getResource("/mcp/mcp-stdio-server.mjs").toURI());
        McpServerTarget target = new McpServerTarget(
                1L, "stdio-pid", "stdio-fixture", "node", "stdio", null, null,
                List.of(fixture.toString()), Map.of("MCP_FIXTURE_VALUE", "env-ok"));

        McpClient stdioClient = client();
        List<McpClient.McpToolInfo> tools = stdioClient.listTools(target);
        Map<String, Object> result =
                stdioClient.callTool(target, "echo", Map.of("value", "arg-ok"));

        assertThat(tools).extracting(McpClient.McpToolInfo::getName)
                .containsExactly("echo");
        assertThat(result.toString()).contains("arg-ok").contains("env-ok");
    }

    @Test
    @DisplayName("stdio policy rejects shells even if an operator lists one")
    void stdio_shellIsAlwaysRejected() {
        McpServerTarget target = new McpServerTarget(
                1L, "shell-pid", "shell", "sh", "stdio", null, null,
                List.of("-c", "echo unsafe"), Map.of());
        McpClient shellClient = new McpClient(
                MAPPER, new McpStdioCommandPolicy("sh"));

        assertThatThrownBy(() -> shellClient.listTools(target))
                .isInstanceOf(McpClient.McpClientException.class)
                .hasMessageContaining("shell executables are forbidden");
    }
}
