package com.auraboot.framework.agent.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Registry for all ToolProvider implementations.
 *
 * <p>Spring auto-injects all beans implementing the {@link ToolProvider} interface.
 * Routes {@link #execute} calls to the correct provider based on which one
 * {@link ToolProvider#handles handles()} the given tool code, and aggregates
 * discovery results across every provider via {@link #discoverAll}.
 *
 * @see ToolProvider
 * @see DslToolProvider
 * @see PlatformToolProvider
 * @see CustomToolProvider
 * @see McpToolProvider
 */
@Service
@Slf4j
public class ToolProviderRegistry {

    private final List<ToolProvider> providers;

    public ToolProviderRegistry(List<ToolProvider> providers) {
        this.providers = providers;
        log.info("ToolProviderRegistry initialized with {} providers: {}",
                providers.size(),
                providers.stream().map(ToolProvider::providerCode).collect(Collectors.joining(", ")));
    }

    /**
     * Execute a tool by routing to the appropriate provider.
     *
     * <p>The provider is selected based on which one {@link ToolProvider#handles handles()} the
     * given {@code toolCode}. If no provider claims the tool code, a failure result is returned
     * without throwing.
     *
     * @param tenantId the tenant context for execution
     * @param toolCode the prefixed tool identifier (e.g. {@code "cmd:crm_account_create"},
     *                 {@code "platform.list_models"})
     * @param params   input parameters for the tool
     * @return execution result; never {@code null}
     */
    public ProviderExecutionResult execute(Long tenantId, String toolCode, Map<String, Object> params) {
        ToolProvider provider = providers.stream()
                .filter(p -> p.handles(toolCode))
                .findFirst()
                .orElse(null);

        if (provider == null) {
            log.warn("No provider handles tool code: {}", toolCode);
            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage("No provider handles tool: " + toolCode)
                    .durationMs(0)
                    .build();
        }

        log.debug("Routing tool {} to provider {}", toolCode, provider.providerCode());
        return provider.execute(tenantId, toolCode, params);
    }

    /**
     * Discover tools from all providers, aggregated and limited by
     * {@link ToolDiscoveryContext#getMaxResults()}.
     *
     * <p>Individual provider discovery failures are caught and logged as warnings so that a single
     * broken provider does not prevent the others from contributing their tools.
     *
     * @param ctx discovery context carrying tenant, model hint, and result limit
     * @return aggregated list of tool definitions; at most {@code ctx.getMaxResults()} entries
     */
    public List<ToolDefinition> discoverAll(ToolDiscoveryContext ctx) {
        return providers.stream()
                .flatMap(p -> {
                    try {
                        return p.discover(ctx).stream();
                    } catch (Exception e) {
                        log.warn("Provider {} discovery failed: {}", p.providerCode(), e.getMessage());
                        return java.util.stream.Stream.empty();
                    }
                })
                .limit(ctx.getMaxResults())
                .toList();
    }

    /**
     * Discover tools from a specific provider identified by {@code providerCode}.
     *
     * @param providerCode the provider to query (e.g. {@code "dsl"}, {@code "platform"})
     * @param ctx          discovery context
     * @return tools from the matching provider; empty list if no such provider is registered
     */
    public List<ToolDefinition> discoverByProvider(String providerCode, ToolDiscoveryContext ctx) {
        return providers.stream()
                .filter(p -> p.providerCode().equals(providerCode))
                .flatMap(p -> p.discover(ctx).stream())
                .toList();
    }

    /**
     * Return the provider codes of all registered providers, in registration order.
     */
    public List<String> getProviderCodes() {
        return providers.stream().map(ToolProvider::providerCode).toList();
    }
}
