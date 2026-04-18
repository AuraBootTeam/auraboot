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
    public List<McpToolInfo> listTools(String serverUrl) {
        Map<String, Object> request = buildRequest("tools/list", Map.of());
        JsonNode response = sendRequest(serverUrl, request);
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
    public Map<String, Object> callTool(String serverUrl, String toolName, Map<String, Object> arguments) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Map.of());

        Map<String, Object> request = buildRequest("tools/call", params);
        JsonNode response = sendRequest(serverUrl, request);
        JsonNode result = extractResult(response);

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

    private JsonNode sendRequest(String serverUrl, Map<String, Object> requestBody) {
        try {
            // SSRF protection with IP pinning (P3-E #1 DNS rebinding TOCTOU).
            SsrfValidator.ValidatedTarget target = SsrfValidator.validate(serverUrl);
            if (target == null) {
                throw new McpClientException("MCP server URL could not be resolved: " + serverUrl);
            }

            String body = objectMapper.writeValueAsString(requestBody);
            log.debug("MCP request to {}: {}", serverUrl, body);

            HttpRequest httpRequest = PinnedHttpRequests.newPinnedRequestBuilder(target)
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

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
