package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.tool.SendCustomerReplyToolHandler;
import com.auraboot.framework.common.util.SsrfValidator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ToolProvider for user-defined custom tools stored in ab_agent_tool.
 * Handles tools with tool_type NOT IN ('dsl_command', 'dsl_query').
 * Supports api_call tool_type: parses source_code as "METHOD URL" and
 * executes an outbound HTTP request with the tool params as JSON body.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomToolProvider implements ToolProvider {

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final SendCustomerReplyToolHandler sendCustomerReplyToolHandler;

    @Override
    public String providerCode() {
        return "custom";
    }

    @Override
    public boolean handles(String toolCode) {
        return toolCode != null && toolCode.startsWith("custom:");
    }

    @Override
    public List<ToolDefinition> discover(ToolDiscoveryContext ctx) {
        // Query user-defined tools that are not DSL-backed (those are handled by DslToolProvider).
        // Tenant isolation is handled explicitly via #{params.tenantId} — using WithoutTenant variant
        // to avoid JSqlParser issues with the NOT IN clause on a non-mt_ table.
        String sql = "SELECT tool_code, tool_name, tool_description, tool_type " +
                "FROM ab_agent_tool " +
                "WHERE tenant_id = #{params.tenantId} " +
                "AND tool_type NOT IN ('dsl_command', 'dsl_query') " +
                "AND tool_status = 'active' " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                "LIMIT #{params.maxResults}";

        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                sql,
                Map.of("tenantId", ctx.getTenantId(), "maxResults", ctx.getMaxResults())
        );

        return rows.stream().map(row -> ToolDefinition.builder()
                .toolCode("custom:" + row.get("tool_code"))
                .toolName((String) row.getOrDefault("tool_name", "Custom Tool"))
                .description((String) row.get("tool_description"))
                .providerCode("custom")
                .toolType((String) row.getOrDefault("tool_type", "custom"))
                .build()
        ).collect(Collectors.toList());
    }

    @Override
    public ProviderExecutionResult execute(Long tenantId, String toolCode, Map<String, Object> params) {
        long start = System.currentTimeMillis();
        String rawCode = toolCode.startsWith("custom:") ? toolCode.substring(7) : toolCode;

        // Route to built-in tool handlers
        if (SendCustomerReplyToolHandler.TOOL_CODE.equals(rawCode)) {
            Map<String, Object> result = sendCustomerReplyToolHandler.execute(params, tenantId);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            return ProviderExecutionResult.builder()
                    .success(success)
                    .data(result)
                    .errorMessage(success ? null : (String) result.get("error"))
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        Map<String, Object> toolDef = loadToolDefinition(tenantId, rawCode);
        if (toolDef == null) {
            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage("Custom tool not found: " + rawCode)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        String toolType = (String) toolDef.getOrDefault("tool_type", "api_call");
        String sourceCode = (String) toolDef.get("source_code");

        if (!"api_call".equals(toolType) || sourceCode == null || sourceCode.isBlank()) {
            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage("Unsupported custom tool type: " + toolType)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        try {
            return executeApiCall(sourceCode, params, start);
        } catch (Exception e) {
            log.error("Custom tool execution failed: tool={}, error={}", rawCode, e.getMessage());
            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Executes an outbound HTTP call described by sourceCode.
     * sourceCode format: "METHOD URL" (e.g. "POST https://hook.example.com/notify")
     * or just a URL (defaults to POST).
     */
    private ProviderExecutionResult executeApiCall(String sourceCode, Map<String, Object> params, long start)
            throws Exception {
        String method = "POST";
        String url = sourceCode.trim();
        if (sourceCode.contains(" ")) {
            String[] parts = sourceCode.split(" ", 2);
            method = parts[0].toUpperCase();
            url = parts[1].trim();
        }

        // SSRF protection: validate URL before making server-side request
        SsrfValidator.validateUrl(url);

        HttpClient httpClient = HttpClient.newHttpClient();
        String bodyJson = objectMapper.writeValueAsString(params != null ? params : Map.of());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30));

        if ("GET".equals(method)) {
            requestBuilder.GET();
        } else {
            requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(bodyJson));
        }

        HttpResponse<String> response = httpClient.send(
                requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

        boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
        log.info("Custom api_call executed: url={}, method={}, status={}, success={}",
                url, method, response.statusCode(), success);

        return ProviderExecutionResult.builder()
                .success(success)
                .data(Map.of("statusCode", response.statusCode(), "body", response.body()))
                .errorMessage(success ? null : "HTTP " + response.statusCode())
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    /**
     * Loads a single active tool definition from ab_agent_tool for the given tenant.
     * Returns null if not found.
     */
    private Map<String, Object> loadToolDefinition(Long tenantId, String toolCode) {
        String sql = "SELECT tool_code, tool_type, source_code, input_schema " +
                "FROM ab_agent_tool " +
                "WHERE tenant_id = #{params.tenantId} " +
                "AND tool_code = #{params.toolCode} " +
                "AND tool_status = 'active' " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                sql, Map.of("tenantId", tenantId, "toolCode", toolCode));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
