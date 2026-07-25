package com.auraboot.framework.agent.provider;

import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP (Model Context Protocol) JSON-RPC 2.0 client over HTTP.
 * <p>
 * Supports two operations:
 * <ul>
 *   <li>{@code tools/list} — discover available tools from an MCP server</li>
 *   <li>{@code tools/call} — invoke a specific tool with arguments</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String JSON_RPC_VERSION = "2.0";
    private static final AtomicLong REQUEST_ID = new AtomicLong(1);

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    /**
     * Discover tools from an MCP server via JSON-RPC {@code tools/list}.
     *
     * @param serverUrl the MCP server HTTP endpoint
     * @return list of tool definitions (each containing name, description, inputSchema)
     * @throws McpClientException if the request fails or response is invalid
     */
    public List<McpToolInfo> listTools(McpServerTarget target) {
        Map<String, Object> request = buildRequest("tools/list", Map.of());
        JsonNode response = sendRequest(target, request);
        JsonNode result = extractResult(response);

        List<McpToolInfo> tools = new ArrayList<>();
        JsonNode toolsNode = result.path("tools");
        if (toolsNode.isArray()) {
            for (JsonNode toolNode : toolsNode) {
                McpToolInfo info = new McpToolInfo();
                info.setName(toolNode.path("name").asText(""));
                info.setDescription(toolNode.path("description").asText(""));
                JsonNode inputSchema = toolNode.path("inputSchema");
                if (!inputSchema.isMissingNode() && !inputSchema.isNull()) {
                    info.setInputSchema(objectMapper.convertValue(inputSchema, Map.class));
                }
                tools.add(info);
            }
        }
        return tools;
    }

    /**
     * Invoke a tool on an MCP server via JSON-RPC {@code tools/call}.
     *
     * @param serverUrl the MCP server HTTP endpoint
     * @param toolName  the tool name to invoke
     * @param arguments the tool arguments
     * @return the call result as a map
     * @throws McpClientException if the request fails or response is invalid
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callTool(McpServerTarget target, String toolName, Map<String, Object> arguments) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Map.of());

        Map<String, Object> request = buildRequest("tools/call", params);
        JsonNode response = sendRequest(target, request);
        JsonNode result = extractResult(response);
        raiseIfToolError(result, toolName);

        return objectMapper.convertValue(result, Map.class);
    }

    // ──────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────

    private Map<String, Object> buildRequest(String method, Map<String, Object> params) {
        Map<String, Object> req = new HashMap<>();
        req.put("jsonrpc", JSON_RPC_VERSION);
        req.put("id", REQUEST_ID.getAndIncrement());
        req.put("method", method);
        req.put("params", params);
        return req;
    }

    /**
     * SSRF-check a URL and capture the resolved IP for connect-time pinning.
     *
     * <p>Overridable purely so protocol tests can reach a loopback test server:
     * rejecting loopback is deliberate here and is pinned by {@code
     * SsrfValidatorTest}, while the headers and credentials this client puts on
     * the wire are independent of that check. Production callers never replace
     * it.
     */
    protected SsrfValidator.ValidatedTarget validateTarget(String serverUrl) {
        return SsrfValidator.validate(serverUrl);
    }

    private JsonNode sendRequest(McpServerTarget server, Map<String, Object> requestBody) {
        String serverUrl = server.serverUrl();

        // A stdio server is a command line, not a URL. Posting to it produced a
        // URI parse error that read like a network fault; say what is actually
        // wrong. stdio is also the column default, so this is the path a server
        // registered with defaults takes.
        if (!server.isHttpTransport()) {
            throw new McpClientException(
                    "MCP server '" + server.serverName() + "' uses transport '"
                            + server.transportType() + "', which is not supported over the network. "
                            + "The platform can only reach MCP servers over HTTP/SSE; stdio servers run as a "
                            + "local child process. Re-register it with an http(s) URL and transport HTTP.");
        }

        try {
            // SSRF protection with IP pinning (P3-E #1 DNS rebinding TOCTOU).
            SsrfValidator.ValidatedTarget target = validateTarget(serverUrl);
            if (target == null) {
                throw new McpClientException("MCP server URL could not be resolved: " + serverUrl);
            }

            String body = objectMapper.writeValueAsString(requestBody);
            log.debug("MCP request to {}: {}", serverUrl, body);

            HttpRequest.Builder builder = PinnedHttpRequests.newPinnedRequestBuilder(target)
                    .header("Content-Type", "application/json")
                    // Streamable HTTP servers answer either with JSON or with an
                    // SSE stream and reject anything that does not accept both
                    // with HTTP 406 — before any tool is reached. Without this
                    // header the client cannot talk to a compliant server at all.
                    .header("Accept", "application/json, text/event-stream")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            applyAuth(server, builder);

            HttpRequest httpRequest = builder.build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new McpClientException(
                        "MCP server returned HTTP " + httpResponse.statusCode() + ": " + httpResponse.body());
            }

            return objectMapper.readTree(httpResponse.body());
        } catch (McpClientException e) {
            throw e;
        } catch (Exception e) {
            throw new McpClientException("Failed to communicate with MCP server at " + serverUrl + ": " + e.getMessage(), e);
        }
    }

    /** Default header for API-key auth when the registration does not name one. */
    private static final String DEFAULT_API_KEY_HEADER = "X-API-Key";

    /**
     * Attach the credentials recorded for this server.
     *
     * <p>These were previously stored and then dropped on the floor — the
     * registry did not select the auth columns and no header was ever set — so
     * a token entered in the admin UI had no effect and every authenticated
     * server answered 401. Values are never logged.
     */
    private void applyAuth(McpServerTarget server, HttpRequest.Builder builder) {
        String authType = server.authType();
        if (authType == null || authType.isBlank() || "NONE".equalsIgnoreCase(authType)) {
            return;
        }
        Map<String, Object> config = server.authConfig();
        if (config == null || config.isEmpty()) {
            log.warn("MCP server '{}' declares auth type {} but has no credentials configured",
                    server.serverName(), authType);
            return;
        }
        String token = asText(config.get("token"));
        if (token == null) {
            log.warn("MCP server '{}' declares auth type {} but its config has no 'token'",
                    server.serverName(), authType);
            return;
        }

        switch (authType.trim().toUpperCase()) {
            case "BEARER" -> builder.header("Authorization", "Bearer " + token);
            case "API_KEY" -> {
                String header = asText(config.get("header"));
                builder.header(header == null || header.isBlank() ? DEFAULT_API_KEY_HEADER : header, token);
            }
            default -> log.warn("MCP server '{}' has unknown auth type '{}'; sending unauthenticated",
                    server.serverName(), authType);
        }
    }

    private static String asText(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * MCP reports a tool-level failure as {@code isError: true} inside an
     * otherwise successful JSON-RPC response. Treating that as success handed
     * the agent an error payload labelled as a result, which it would then
     * summarise back to the user as though the operation had worked.
     */
    private void raiseIfToolError(JsonNode result, String toolName) {
        if (!result.path("isError").asBoolean(false)) {
            return;
        }
        StringBuilder detail = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String text = item.path("text").asText("");
                if (!text.isBlank()) {
                    detail.append(detail.isEmpty() ? "" : " ").append(text);
                }
            }
        }
        throw new McpClientException("MCP tool '" + toolName + "' reported an error: "
                + (detail.isEmpty() ? "no detail supplied" : detail));
    }

    private JsonNode extractResult(JsonNode response) {
        if (response == null) {
            throw new McpClientException("MCP server returned null response");
        }
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String errorMsg = error.path("message").asText("Unknown error");
            int errorCode = error.path("code").asInt(0);
            throw new McpClientException("MCP JSON-RPC error " + errorCode + ": " + errorMsg);
        }
        JsonNode result = response.path("result");
        if (result.isMissingNode() || result.isNull()) {
            throw new McpClientException("MCP response missing 'result' field");
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────
    // Inner types
    // ──────────────────────────────────────────────────────────────

    /**
     * Tool metadata returned by {@code tools/list}.
     */
    @lombok.Data
    public static class McpToolInfo {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;
    }

    /**
     * Exception for MCP client communication errors.
     */
    public static class McpClientException extends RuntimeException {
        public McpClientException(String message) {
            super(message);
        }

        public McpClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
