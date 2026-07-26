package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.service.McpServerConfigChangedEvent;
import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Lifecycle-managed MCP client backed by the official Java SDK.
 *
 * <p>Each registry row owns at most one initialized SDK session. Streamable
 * HTTP session identifiers and stdio child processes therefore survive across
 * {@code tools/list} and {@code tools/call}; configuration events close the old
 * session before it can be reused.
 */
@Slf4j
@Component
public class McpClient implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String DEFAULT_API_KEY_HEADER = "X-API-Key";
    private static final String CLIENT_NAME = "auraboot-platform";
    private static final String CLIENT_VERSION = "5.1";

    private final ObjectMapper objectMapper;
    private final JacksonMcpJsonMapper jsonMapper;
    private final McpStdioCommandPolicy stdioCommandPolicy;
    private final ConcurrentMap<String, ManagedClient> clients = new ConcurrentHashMap<>();

    public McpClient(ObjectMapper objectMapper, McpStdioCommandPolicy stdioCommandPolicy) {
        this.objectMapper = objectMapper;
        this.jsonMapper = new JacksonMcpJsonMapper(objectMapper);
        this.stdioCommandPolicy = stdioCommandPolicy;
    }

    /** Discover every page of tools from an initialized MCP session. */
    public List<McpToolInfo> listTools(McpServerTarget target) {
        try {
            McpSyncClient client = clientFor(target).client();
            List<McpToolInfo> tools = new ArrayList<>();
            McpSchema.ListToolsResult result = client.listTools();
            if (result != null && result.tools() != null) {
                for (McpSchema.Tool tool : result.tools()) {
                    McpToolInfo info = new McpToolInfo();
                    info.setName(tool.name());
                    info.setDescription(tool.description());
                    info.setInputSchema(tool.inputSchema());
                    tools.add(info);
                }
            }
            return tools;
        } catch (RuntimeException error) {
            invalidateAfterFailure(target, error);
            throw communicationFailure(target, error);
        }
    }

    /** Invoke a tool through the same initialized session used for discovery. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callTool(
            McpServerTarget target, String toolName, Map<String, Object> arguments) {
        try {
            McpSchema.CallToolResult result = clientFor(target).client().callTool(
                    McpSchema.CallToolRequest.builder(toolName)
                            .arguments(arguments == null ? Map.of() : arguments)
                            .build());
            raiseIfToolError(result, toolName);
            Map<String, Object> converted = objectMapper.convertValue(result, Map.class);
            return converted == null ? Map.of() : converted;
        } catch (McpClientException error) {
            throw error;
        } catch (RuntimeException error) {
            invalidateAfterFailure(target, error);
            throw communicationFailure(target, error);
        }
    }

    /**
     * SSRF-check a URL and capture the resolved IP for connect-time pinning.
     *
     * <p>Protocol tests override this method only for an in-process loopback
     * server. Production always uses the platform validator.
     */
    protected SsrfValidator.ValidatedTarget validateTarget(String serverUrl) {
        return SsrfValidator.validate(serverUrl);
    }

    private ManagedClient clientFor(McpServerTarget target) {
        if (target == null) {
            throw new McpClientException("MCP server target is required");
        }
        String key = target.connectionKey();
        int fingerprint = target.configFingerprint();
        return clients.compute(key, (ignored, existing) -> {
            if (existing != null && existing.fingerprint() == fingerprint) {
                return existing;
            }
            closeQuietly(existing);
            McpSyncClient client = buildClient(target);
            try {
                client.initialize();
                return new ManagedClient(fingerprint, client);
            } catch (RuntimeException error) {
                closeQuietly(new ManagedClient(fingerprint, client));
                throw error;
            }
        });
    }

    private McpSyncClient buildClient(McpServerTarget target) {
        McpClientTransport transport = switch (target.normalizedTransport()) {
            case "streamable_http" -> streamableHttpTransport(target);
            case "sse" -> sseTransport(target);
            case "stdio" -> stdioTransport(target);
            default -> throw new McpClientException(
                    "Unsupported MCP transport: " + target.transportType());
        };
        return io.modelcontextprotocol.client.McpClient.sync(transport)
                .requestTimeout(TIMEOUT)
                .initializationTimeout(TIMEOUT)
                .clientInfo(McpSchema.Implementation
                        .builder(CLIENT_NAME, CLIENT_VERSION)
                        .build())
                // AuraBoot already standardizes on networknt 1.x. Supplying an
                // explicit adapter prevents the SDK's Jackson service-loader
                // from pulling networknt 2.x classes into the same package.
                .jsonSchemaValidator(this::validateJsonSchema)
                .enableCallToolSchemaCaching(true)
                .build();
    }

    private JsonSchemaValidator.ValidationResponse validateJsonSchema(
            Map<String, Object> schema, Object value) {
        try {
            Set<ValidationMessage> errors = JsonSchemaFactory
                    .getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(objectMapper.valueToTree(schema))
                    .validate(objectMapper.valueToTree(value));
            if (!errors.isEmpty()) {
                String detail = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .sorted()
                        .limit(5)
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("JSON schema validation failed");
                return JsonSchemaValidator.ValidationResponse.asInvalid(detail);
            }
            return JsonSchemaValidator.ValidationResponse.asValid(
                    objectMapper.writeValueAsString(value));
        } catch (Exception error) {
            return JsonSchemaValidator.ValidationResponse.asInvalid(
                    "JSON schema validation failed: " + error.getMessage());
        }
    }

    private McpClientTransport streamableHttpTransport(McpServerTarget target) {
        HttpTarget http = prepareHttpTarget(target);
        return HttpClientStreamableHttpTransport.builder(http.baseUri())
                .endpoint(http.endpoint())
                .requestBuilder(http.requestBuilder())
                .jsonMapper(jsonMapper)
                .connectTimeout(TIMEOUT)
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        applyAuth(target, builder))
                .build();
    }

    private McpClientTransport sseTransport(McpServerTarget target) {
        HttpTarget http = prepareHttpTarget(target);
        return HttpClientSseClientTransport.builder(http.baseUri())
                .sseEndpoint(http.endpoint())
                .requestBuilder(http.requestBuilder())
                .jsonMapper(jsonMapper)
                .connectTimeout(TIMEOUT)
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        applyAuth(target, builder))
                .build();
    }

    private HttpTarget prepareHttpTarget(McpServerTarget target) {
        SsrfValidator.ValidatedTarget validated = validateTarget(target.serverUrl());
        if (validated == null) {
            throw new McpClientException(
                    "MCP server '" + target.serverName() + "' could not be resolved");
        }
        HttpRequest.Builder requestBuilder =
                PinnedHttpRequests.newPinnedRequestBuilder(validated);
        URI effective = requestBuilder.build().uri();
        String baseUri = effective.getScheme() + "://" + effective.getRawAuthority();
        String endpoint = effective.getRawPath();
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "/mcp";
        }
        if (effective.getRawQuery() != null) {
            endpoint += "?" + effective.getRawQuery();
        }
        return new HttpTarget(baseUri, endpoint, requestBuilder);
    }

    private McpClientTransport stdioTransport(McpServerTarget target) {
        stdioCommandPolicy.validate(target);
        ServerParameters parameters = ServerParameters.builder(target.serverUrl())
                .args(target.stdioArgs() == null ? List.of() : target.stdioArgs())
                .env(target.stdioEnv() == null ? Map.of() : target.stdioEnv())
                .build();
        StdioClientTransport transport = new StdioClientTransport(parameters, jsonMapper);
        // Child stderr is remote/untrusted output and may echo credentials from
        // its environment. Record only that output occurred, never its content.
        transport.setStdErrorHandler(line ->
                log.debug("MCP stdio server '{}' emitted stderr", target.serverName()));
        return transport;
    }

    private void applyAuth(McpServerTarget server, HttpRequest.Builder builder) {
        String authType = server.authType();
        if (authType == null || authType.isBlank() || "none".equalsIgnoreCase(authType)) {
            return;
        }
        Map<String, Object> config = server.authConfig();
        String token = config == null ? null : asText(config.get("token"));
        if (token == null) {
            throw new McpClientException(
                    "MCP server '" + server.serverName() + "' requires credentials");
        }
        switch (authType.trim().toLowerCase()) {
            case "bearer" -> builder.header("Authorization", "Bearer " + token);
            case "api_key" -> {
                String header = asText(config.get("header"));
                builder.header(header == null ? DEFAULT_API_KEY_HEADER : header, token);
            }
            default -> throw new McpClientException(
                    "Unsupported MCP authentication type: " + authType);
        }
    }

    private void raiseIfToolError(McpSchema.CallToolResult result, String toolName) {
        if (result == null) {
            throw new McpClientException("MCP tool '" + toolName + "' returned no result");
        }
        if (!Boolean.TRUE.equals(result.isError())) {
            return;
        }
        StringBuilder detail = new StringBuilder();
        if (result.content() != null) {
            for (McpSchema.Content item : result.content()) {
                if (item instanceof McpSchema.TextContent text && !text.text().isBlank()) {
                    detail.append(detail.isEmpty() ? "" : " ").append(text.text());
                }
            }
        }
        throw new McpClientException("MCP tool '" + toolName + "' reported an error: "
                + (detail.isEmpty() ? "no detail supplied" : detail));
    }

    private McpClientException communicationFailure(
            McpServerTarget target, RuntimeException error) {
        String detail = error.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = error.getClass().getSimpleName();
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String rootDetail = root.getMessage();
        if (root != error
                && rootDetail != null
                && !rootDetail.isBlank()
                && !detail.contains(rootDetail)) {
            detail += " (" + rootDetail + ")";
        }
        return new McpClientException(
                "Failed to communicate with MCP server '" + target.serverName()
                        + "': " + detail,
                error);
    }

    private void invalidateAfterFailure(McpServerTarget target, RuntimeException error) {
        ManagedClient removed = clients.remove(target.connectionKey());
        closeQuietly(removed);
        log.warn("Invalidated MCP session after failure: server='{}', errorType={}",
                target.serverName(), error.getClass().getSimpleName());
    }

    /** Close the exact session affected by a persisted configuration change. */
    @EventListener
    public void onServerConfigChanged(McpServerConfigChangedEvent event) {
        if (event == null) {
            return;
        }
        String key = String.valueOf(event.tenantId()) + ":" + event.pid();
        closeQuietly(clients.remove(key));
    }

    @Override
    @PreDestroy
    public void close() {
        Map<String, ManagedClient> snapshot = new LinkedHashMap<>(clients);
        clients.clear();
        snapshot.values().forEach(this::closeQuietly);
    }

    private void closeQuietly(ManagedClient managed) {
        if (managed == null) {
            return;
        }
        try {
            managed.client().close();
        } catch (RuntimeException closeError) {
            log.debug("MCP session close failed: {}", closeError.getMessage());
        }
    }

    private static String asText(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private record ManagedClient(int fingerprint, McpSyncClient client) {
    }

    private record HttpTarget(
            String baseUri, String endpoint, HttpRequest.Builder requestBuilder) {
    }

    /** Tool metadata returned by {@code tools/list}. */
    @Data
    public static class McpToolInfo {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;
    }

    /** Exception for MCP client transport, protocol, or tool-level failures. */
    public static class McpClientException extends RuntimeException {
        public McpClientException(String message) {
            super(message);
        }

        public McpClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
