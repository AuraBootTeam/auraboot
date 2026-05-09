package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Factory for resolving LLM providers and their configuration.
 * <p>
 * Fully data-driven: provider definitions come from CloudConfig (ab_cloud_config with service_type='llm').
 * Each CloudConfig row stores apiFormat ("messages" or "chat_completions") in its config JSON.
 *
 * Configuration resolution order:
 * 1. CloudConfig (ab_cloud_config with service_type='llm') — recommended, supports tenant override
 * 2. AgentProperties (application.yml agent.anthropic.*) — fallback for backward compatibility
 *
 * Provider routing:
 * - apiFormat="messages" → AnthropicLlmProvider (Claude Messages API)
 * - apiFormat="chat_completions" (default) → OpenAiCompatibleLlmProvider (Chat Completions API)
 */
@Slf4j
@Component
public class LlmProviderFactory {

    private final Map<String, LlmProvider> providerMap;
    private final LlmProvider openAiCompatible;
    private final CloudConfigService cloudConfigService;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    /**
     * Global kill-switch for the stub LLM provider. When true, every
     * {@link #resolveConfig} call returns a stub-key config regardless of
     * CloudConfig / yml content, and {@link #getProvider} routes to the stub
     * bean. Defaults to false so production is unaffected.
     */
    @Value("${agent.llm.stub-mode:false}")
    private boolean stubMode;

    public LlmProviderFactory(List<LlmProvider> providers, CloudConfigService cloudConfigService,
                               AgentProperties agentProperties, ObjectMapper objectMapper) {
        this.cloudConfigService = cloudConfigService;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;

        this.providerMap = new HashMap<>();
        LlmProvider openAiRef = null;
        for (LlmProvider p : providers) {
            providerMap.put(p.getProviderCode(), p);
            if ("openai".equals(p.getProviderCode())) {
                openAiRef = p;
            }
        }
        this.openAiCompatible = openAiRef;
    }

    /**
     * Resolve the LlmProvider implementation for a given provider code.
     * Uses apiFormat from CloudConfig to decide: "messages" → Anthropic bean, otherwise → OpenAI-compatible bean.
     */
    public LlmProvider getProvider(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            providerCode = "anthropic";
        }

        // Stub-mode short-circuit: when the operator opts into stub-mode, every
        // provider lookup resolves to the stub bean so AuraBot E2E suites can
        // exercise the chat pipeline without real provider credentials.
        if (stubMode) {
            LlmProvider stub = providerMap.get(StubLlmProvider.PROVIDER_CODE);
            if (stub != null) return stub;
        }
        // Explicit stub provider code from config.
        if (StubLlmProvider.PROVIDER_CODE.equals(providerCode)) {
            LlmProvider stub = providerMap.get(StubLlmProvider.PROVIDER_CODE);
            if (stub != null) return stub;
        }

        // Check for a dedicated bean first (anthropic, openai)
        LlmProvider dedicated = providerMap.get(providerCode);
        if (dedicated != null) return dedicated;

        // Resolve apiFormat from CloudConfig to pick the right implementation
        String apiFormat = resolveApiFormat(providerCode);
        if ("messages".equals(apiFormat)) {
            return providerMap.get("anthropic");
        }

        // Default: chat_completions → OpenAI-compatible
        if (openAiCompatible != null) return openAiCompatible;

        log.warn("No OpenAI-compatible provider bean available, falling back to anthropic for: {}", providerCode);
        return providerMap.get("anthropic");
    }

    /**
     * Resolve provider configuration: API key, base URL, model.
     * Tries CloudConfig first, falls back to application.yml for anthropic.
     */
    @SuppressWarnings("unchecked")
    public ProviderConfig resolveConfig(Long tenantId, String providerCode) {
        // Stub-mode short-circuit: bypass CloudConfig / yml entirely. The
        // returned config carries the sentinel key so downstream code that
        // re-routes by API-key (e.g. {@link #getProvider}) also lands on the
        // stub bean.
        if (stubMode) {
            return stubProviderConfig();
        }

        // No provider specified — find the first enabled provider with an API key
        if (providerCode == null || providerCode.isBlank()) {
            List<ProviderInfo> configured = listConfiguredProviders(tenantId);
            if (configured.isEmpty()) {
                // Fallback chain when nothing real is configured:
                //   1. application.yml agent.anthropic.api-key (set to the
                //      stub sentinel by default in dev/E2E) → stub config.
                //   2. Otherwise null, matching legacy behaviour.
                ProviderConfig ymlFallback = ymlAnthropicFallback();
                if (ymlFallback != null) {
                    return ymlFallback;
                }
                log.warn("No LLM provider configured for tenant {}", tenantId);
                return null;
            }
            providerCode = configured.get(0).getProviderCode();
            log.debug("Auto-resolved LLM provider: {}", providerCode);
        }

        // Explicit stub provider code requested.
        if (StubLlmProvider.PROVIDER_CODE.equals(providerCode)) {
            return stubProviderConfig();
        }

        // Look up the specific provider in CloudConfig
        try {
            CloudConfig cc = cloudConfigService.getEffectiveConfig(tenantId, "llm", providerCode);
            if (cc != null && cc.getConfig() != null && !cc.getConfig().isBlank()) {
                Map<String, Object> config = objectMapper.readValue(cc.getConfig(), Map.class);
                String apiKey = (String) config.get("apiKey");
                if (apiKey != null && !apiKey.isBlank()) {
                    return ProviderConfig.builder()
                            .providerCode(providerCode)
                            .apiKey(apiKey)
                            .baseUrl(getConfigString(config, "baseUrl", "https://api.openai.com"))
                            .defaultModel(getConfigString(config, "defaultModel", "gpt-4o"))
                            .maxTokens(resolveMaxTokens(config))
                            .build();
                }
            }
        } catch (Exception e) {
            log.debug("CloudConfig lookup failed for LLM/{}: {}", providerCode, e.getMessage());
        }

        // application.yml fallback (anthropic only). When the yml key is the
        // stub sentinel, the returned config will route to the stub provider
        // via {@link #getProvider}'s API-key recognition.
        if ("anthropic".equals(providerCode)) {
            ProviderConfig ymlFallback = ymlAnthropicFallback();
            if (ymlFallback != null) {
                return ymlFallback;
            }
        }

        log.warn("LLM provider '{}' not configured or missing API key for tenant {}", providerCode, tenantId);
        return null;
    }

    /**
     * Build a {@link ProviderConfig} pointing at the stub provider. Used both
     * when {@code agent.llm.stub-mode=true} and when the resolved Anthropic key
     * happens to equal {@link StubLlmProvider#STUB_API_KEY_SENTINEL}.
     */
    private ProviderConfig stubProviderConfig() {
        return ProviderConfig.builder()
                .providerCode(StubLlmProvider.PROVIDER_CODE)
                .apiKey(StubLlmProvider.STUB_API_KEY_SENTINEL)
                .baseUrl("stub://local")
                .defaultModel("stub-model")
                .maxTokens(4096)
                .build();
    }

    /**
     * Resolve the {@code agent.anthropic.*} block from {@code application.yml}
     * into a {@link ProviderConfig}. Returns null when the yml key is blank.
     * When the key equals {@link StubLlmProvider#STUB_API_KEY_SENTINEL}, a
     * stub-routed config is returned instead so the request falls into the
     * stub bean rather than the real Anthropic provider.
     */
    private ProviderConfig ymlAnthropicFallback() {
        AgentProperties.Anthropic anthropic = agentProperties.getAnthropic();
        if (anthropic == null) return null;
        String key = anthropic.getApiKey();
        if (key == null || key.isBlank()) return null;
        if (StubLlmProvider.STUB_API_KEY_SENTINEL.equals(key)) {
            return stubProviderConfig();
        }
        return ProviderConfig.builder()
                .providerCode("anthropic")
                .apiKey(key)
                .baseUrl(anthropic.getBaseUrl())
                .defaultModel(anthropic.getDefaultModel())
                .maxTokens(anthropic.getMaxTokens())
                .build();
    }

    /**
     * List all known LLM providers from CloudConfig.
     * Each provider's configured status is determined by whether an apiKey exists.
     */
    @SuppressWarnings("unchecked")
    public List<ProviderInfo> listAllProviders() {
        List<ProviderInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            List<CloudConfig> configs = cloudConfigService.getAllByServiceType("llm");
            for (CloudConfig cc : configs) {
                if (seen.contains(cc.getProviderCode())) continue;
                seen.add(cc.getProviderCode());

                String displayName = cc.getProviderCode();
                String apiFormat = "chat_completions";
                boolean hasKey = false;
                try {
                    Map<String, Object> cfg = objectMapper.readValue(cc.getConfig(), Map.class);
                    if (cfg.get("displayName") != null) displayName = (String) cfg.get("displayName");
                    if (cfg.get("apiFormat") != null) apiFormat = (String) cfg.get("apiFormat");
                    String apiKey = (String) cfg.get("apiKey");
                    hasKey = apiKey != null && !apiKey.isBlank();
                } catch (Exception ignored) {}

                result.add(ProviderInfo.builder()
                        .providerCode(cc.getProviderCode())
                        .displayName(displayName)
                        .apiFormat(apiFormat)
                        .configured(hasKey)
                        .build());
            }
        } catch (Exception e) {
            log.debug("Failed to list LLM providers from CloudConfig: {}", e.getMessage());
        }

        // Yml fallback for anthropic
        if (!seen.contains("anthropic")) {
            AgentProperties.Anthropic anthropic = agentProperties.getAnthropic();
            boolean hasKey = anthropic.getApiKey() != null && !anthropic.getApiKey().isBlank();
            if (hasKey) {
                result.add(ProviderInfo.builder()
                        .providerCode("anthropic").displayName("Anthropic (Claude)")
                        .apiFormat("messages").source("application_yml").configured(true)
                        .build());
            }
        }

        return result;
    }

    /**
     * List configured (with API key) LLM providers for a specific tenant.
     */
    @SuppressWarnings("unchecked")
    public List<ProviderInfo> listConfiguredProviders(Long tenantId) {
        List<ProviderInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            List<CloudConfig> configs = cloudConfigService.getEnabledProviders(tenantId, "llm");
            for (CloudConfig cc : configs) {
                if (seen.contains(cc.getProviderCode())) continue;
                seen.add(cc.getProviderCode());

                String displayName = cc.getProviderCode();
                String apiFormat = "chat_completions";
                boolean hasKey = false;
                try {
                    Map<String, Object> cfg = objectMapper.readValue(cc.getConfig(), Map.class);
                    if (cfg.get("displayName") != null) displayName = (String) cfg.get("displayName");
                    if (cfg.get("apiFormat") != null) apiFormat = (String) cfg.get("apiFormat");
                    String apiKey = (String) cfg.get("apiKey");
                    hasKey = apiKey != null && !apiKey.isBlank();
                } catch (Exception ignored) {}

                if (!hasKey) continue; // Skip providers without API key

                result.add(ProviderInfo.builder()
                        .providerCode(cc.getProviderCode()).displayName(displayName)
                        .apiFormat(apiFormat).source("cloud_config").configured(true)
                        .build());
            }
        } catch (Exception e) {
            log.debug("Failed to list configured LLM providers: {}", e.getMessage());
        }

        // Yml fallback for anthropic
        if (!seen.contains("anthropic")) {
            AgentProperties.Anthropic anthropic = agentProperties.getAnthropic();
            if (anthropic.getApiKey() != null && !anthropic.getApiKey().isBlank()) {
                result.add(ProviderInfo.builder()
                        .providerCode("anthropic").displayName("Anthropic (Claude)")
                        .apiFormat("messages").source("application_yml").configured(true)
                        .build());
            }
        }

        return result;
    }

    /**
     * Resolve provider code from a model name by checking CloudConfig models/defaultModel,
     * then falling back to name-based heuristics.
     *
     * @param model the model name (e.g., "claude-sonnet-4-6", "gpt-4o", "deepseek-chat")
     * @return the provider code, or null if unresolvable
     */
    @SuppressWarnings("unchecked")
    public String resolveProviderByModel(String model) {
        if (model == null || model.isBlank()) return null;
        String modelLower = model.toLowerCase();

        // 1. Check CloudConfig: models array and defaultModel
        try {
            List<CloudConfig> configs = cloudConfigService.getAllByServiceType("llm");
            for (CloudConfig cc : configs) {
                if (cc.getConfig() == null) continue;
                try {
                    Map<String, Object> cfg = objectMapper.readValue(cc.getConfig(), Map.class);
                    // Check models array
                    Object modelsObj = cfg.get("models");
                    if (modelsObj instanceof List<?> modelsList) {
                        for (Object m : modelsList) {
                            if (m instanceof String ms && modelLower.contains(ms.toLowerCase())) {
                                return cc.getProviderCode();
                            }
                        }
                    }
                    // Check defaultModel
                    String dm = (String) cfg.get("defaultModel");
                    if (dm != null && modelLower.contains(dm.toLowerCase())) {
                        return cc.getProviderCode();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // 2. Name-based fallback heuristics
        if (modelLower.contains("claude")) return "anthropic";
        if (modelLower.contains("gpt") || modelLower.startsWith("o1-") || modelLower.startsWith("o3-") || modelLower.startsWith("o4-")) return "openai";
        if (modelLower.contains("deepseek")) return "deepseek";
        if (modelLower.contains("minimax") || modelLower.contains("abab")) return "minimaxi";
        if (modelLower.contains("qwen")) return "qianwen";
        if (modelLower.contains("glm")) return "zhipu";
        if (modelLower.contains("moonshot")) return "moonshot";

        return null;
    }

    /**
     * Get the default model for a provider code from CloudConfig, with fallback.
     *
     * @param providerCode the provider code
     * @return the default model name
     */
    @SuppressWarnings("unchecked")
    public String getDefaultModel(String providerCode) {
        if (providerCode == null) return "claude-sonnet-4-6";

        try {
            List<CloudConfig> configs = cloudConfigService.getAllByServiceType("llm");
            for (CloudConfig cc : configs) {
                if (providerCode.equals(cc.getProviderCode()) && cc.getConfig() != null) {
                    Map<String, Object> cfg = objectMapper.readValue(cc.getConfig(), Map.class);
                    String dm = (String) cfg.get("defaultModel");
                    if (dm != null && !dm.isBlank()) return dm;
                }
            }
        } catch (Exception ignored) {}

        // Hardcoded fallback for the most common provider
        return "anthropic".equals(providerCode) ? "claude-sonnet-4-6" : "gpt-4o";
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Resolve apiFormat for a provider from CloudConfig.
     * Returns "messages" for Anthropic-style API, "chat_completions" for OpenAI-compatible.
     */
    @SuppressWarnings("unchecked")
    private String resolveApiFormat(String providerCode) {
        try {
            List<CloudConfig> configs = cloudConfigService.getAllByServiceType("llm");
            for (CloudConfig cc : configs) {
                if (providerCode.equals(cc.getProviderCode()) && cc.getConfig() != null) {
                    Map<String, Object> cfg = objectMapper.readValue(cc.getConfig(), Map.class);
                    String fmt = (String) cfg.get("apiFormat");
                    if (fmt != null) return fmt;
                }
            }
        } catch (Exception ignored) {}

        // Fallback: anthropic uses messages API, everything else uses chat_completions
        return "anthropic".equals(providerCode) ? "messages" : "chat_completions";
    }

    private String getConfigString(Map<String, Object> config, String key, String fallback) {
        String val = (String) config.get(key);
        return (val != null && !val.isBlank()) ? val : fallback;
    }

    private int resolveMaxTokens(Map<String, Object> config) {
        Object maxTokens = config.get("maxTokens");
        if (maxTokens instanceof Number n) return n.intValue();
        return 4096;
    }

    // =========================================================================
    // Inner classes
    // =========================================================================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProviderConfig {
        private String providerCode;
        private String apiKey;
        private String baseUrl;
        private String defaultModel;
        private int maxTokens;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProviderInfo {
        private String providerCode;
        private String displayName;
        private String apiFormat;
        private String source;
        private boolean configured;
    }
}
