package com.auraboot.framework.agent.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.auraboot.framework.common.util.SsrfValidator;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Protocol-level tests for {@link McpClient} against a real HTTP server.
 *
 * <p>These deliberately do not mock the client. Every other MCP test in the
 * suite stubs {@code McpClient} out, which is why the client could sit in the
 * tree unable to complete a handshake with any compliant MCP server — including
 * AuraBoot's own {@code aura mcp serve --http} — while the suite stayed green.
 * A spec-compliant Streamable HTTP server rejects a request that does not accept
 * {@code text/event-stream} with HTTP 406 before any tool is reached, so the
 * headers the client puts on the wire are the behaviour under test.
 */
class McpClientProtocolTest {

    private HttpServer server;
    private String baseUrl;

    /** Headers of the most recent request, lower-cased keys. */
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private volatile String lastBody = "";
    private volatile String lastRequestUri;
    private volatile String cannedResponse = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastRequestUri = exchange.getRequestURI().toString();
        exchange.getRequestHeaders()
                .forEach((k, v) -> lastHeaders.put(k.toLowerCase(), String.join(", ", v)));
        try (InputStream in = exchange.getRequestBody()) {
            lastBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        byte[] body = cannedResponse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /**
     * Client pointed at the loopback test server. SSRF rejects loopback by
     * design (pinned by {@code SsrfValidatorTest}); overriding the check is what
     * lets the header/credential behaviour be exercised over a real socket
     * rather than against a mock.
     */
    private McpClient client() {
        return new McpClient(new ObjectMapper()) {
            @Override
            protected SsrfValidator.ValidatedTarget validateTarget(String serverUrl) {
                URI uri = URI.create(serverUrl);
                try {
                    return new SsrfValidator.ValidatedTarget(
                            uri,
                            uri.getHost(),
                            InetAddress.getByName("127.0.0.1"),
                            uri.getPort(),
                            uri.getScheme());
                } catch (UnknownHostException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static McpServerTarget httpTarget(String url) {
        return new McpServerTarget("test-server", url, "HTTP", null, null);
    }

    // ──────────────────────────────────────────────────────────────
    // Accept header — the difference between working and HTTP 406
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listTools accepts both JSON and SSE so Streamable HTTP servers do not 406")
    void listTools_sendsSpecCompliantAcceptHeader() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}";

        client().listTools(httpTarget(baseUrl));

        assertThat(lastHeaders.get("accept"))
                .contains("application/json")
                .contains("text/event-stream");
    }

    @Test
    @DisplayName("callTool accepts both JSON and SSE")
    void callTool_sendsSpecCompliantAcceptHeader() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[]}}";

        client().callTool(httpTarget(baseUrl), "some_tool", Map.of());

        assertThat(lastHeaders.get("accept"))
                .contains("application/json")
                .contains("text/event-stream");
    }

    // ──────────────────────────────────────────────────────────────
    // Auth — registered credentials must reach the wire
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BEARER auth puts the configured token in the Authorization header")
    void bearerAuth_isSentOnTheWire() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}";
        McpServerTarget target = new McpServerTarget(
                "github", baseUrl, "HTTP", "BEARER", Map.of("token", "ghp_secret"));

        client().listTools(target);

        assertThat(lastHeaders.get("authorization")).isEqualTo("Bearer ghp_secret");
    }

    @Test
    @DisplayName("API_KEY auth uses the configured header name")
    void apiKeyAuth_usesConfiguredHeaderName() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}";
        McpServerTarget target = new McpServerTarget(
                "vendor", baseUrl, "HTTP", "API_KEY",
                Map.of("header", "X-API-Key", "token", "k-123"));

        client().listTools(target);

        assertThat(lastHeaders.get("x-api-key")).isEqualTo("k-123");
    }

    @Test
    @DisplayName("API_KEY auth defaults to X-API-Key when no header name is configured")
    void apiKeyAuth_defaultsHeaderName() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}";
        McpServerTarget target = new McpServerTarget(
                "vendor", baseUrl, "HTTP", "API_KEY", Map.of("token", "k-456"));

        client().listTools(target);

        assertThat(lastHeaders.get("x-api-key")).isEqualTo("k-456");
    }

    @Test
    @DisplayName("no auth configured sends no Authorization header")
    void noAuth_sendsNoAuthorizationHeader() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}";

        client().listTools(httpTarget(baseUrl));

        assertThat(lastHeaders.get("authorization")).isNull();
    }

    // ──────────────────────────────────────────────────────────────
    // Transport — stdio must not be posted at as if it were a URL
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stdio transport fails with an actionable message instead of an HTTP attempt")
    void stdioTransport_failsWithActionableMessage() {
        McpServerTarget target = new McpServerTarget(
                "github", "npx -y @modelcontextprotocol/server-github", "STDIO", null, null);

        assertThatThrownBy(() -> client().listTools(target))
                .isInstanceOf(McpClient.McpClientException.class)
                .hasMessageContaining("stdio")
                .hasMessageContaining("not supported");
    }

    // ──────────────────────────────────────────────────────────────
    // isError — a tool-level failure is not a success
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tools/call result with isError:true is surfaced as a failure")
    void callTool_isErrorTrue_raises() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":true,"
                + "\"content\":[{\"type\":\"text\",\"text\":\"repo not found\"}]}}";

        assertThatThrownBy(() -> client().callTool(httpTarget(baseUrl), "search", Map.of()))
                .isInstanceOf(McpClient.McpClientException.class)
                .hasMessageContaining("repo not found");
    }

    @Test
    @DisplayName("tools/call result without isError is returned normally")
    void callTool_success_returnsResult() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":"
                + "[{\"type\":\"text\",\"text\":\"ok\"}]}}";

        Map<String, Object> result = client().callTool(httpTarget(baseUrl), "search", Map.of());

        assertThat(result).containsKey("content");
    }

    // ──────────────────────────────────────────────────────────────
    // Existing behaviour that must not regress
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listTools parses tool definitions from the response")
    void listTools_parsesTools() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
                + "{\"name\":\"search\",\"description\":\"Search things\","
                + "\"inputSchema\":{\"type\":\"object\"}}]}}";

        List<McpClient.McpToolInfo> tools = client().listTools(httpTarget(baseUrl));

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getName()).isEqualTo("search");
        assertThat(tools.get(0).getDescription()).isEqualTo("Search things");
        assertThat(tools.get(0).getInputSchema()).containsEntry("type", "object");
    }

    @Test
    @DisplayName("JSON-RPC error responses raise McpClientException")
    void jsonRpcError_raises() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":"
                + "{\"code\":-32601,\"message\":\"Method not found\"}}";

        assertThatThrownBy(() -> client().listTools(httpTarget(baseUrl)))
                .isInstanceOf(McpClient.McpClientException.class)
                .hasMessageContaining("Method not found");
    }

    @Test
    @DisplayName("the request body is a well-formed JSON-RPC 2.0 envelope")
    void requestBody_isJsonRpcEnvelope() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}";

        client().listTools(httpTarget(baseUrl));

        assertThat(lastBody).contains("\"jsonrpc\":\"2.0\"");
        assertThat(lastBody).contains("\"method\":\"tools/list\"");
    }

    /**
     * Credentials belong in a header, never in the URI — request lines are the
     * most commonly logged part of an HTTP call (proxies, access logs, the
     * server's own logging), so a token there leaks far more widely than one in
     * a header. Asserts on the URI the server actually received; asserting on
     * the locally-built URL would hold no matter what the client did.
     */
    @Test
    @DisplayName("credentials are not placed in the request URI")
    void credentials_neverInUri() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}";
        String secret = "ghp_secret";
        McpServerTarget target = new McpServerTarget(
                "github", baseUrl, "HTTP", "BEARER", Map.of("token", secret));

        client().listTools(target);

        assertThat(lastRequestUri).isNotNull().doesNotContain(secret);
        assertThat(lastBody).doesNotContain(secret);
        // The credential did travel — otherwise "absent from the URI" is vacuous.
        assertThat(lastHeaders.get("authorization")).contains(secret);
    }
}
