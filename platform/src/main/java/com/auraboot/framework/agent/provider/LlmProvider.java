package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;

/**
 * Abstraction for LLM providers (Anthropic, OpenAI, DeepSeek, Qianwen, etc.).
 * Each provider translates between the unified request/response format and
 * their specific API format (Anthropic Messages API vs OpenAI Chat Completions API).
 */
public interface LlmProvider {

    /** Provider code: "anthropic", "openai", "deepseek", "qianwen", "zhipu", etc. */
    String getProviderCode();

    /** Human-readable display name */
    String getDisplayName();

    /** Whether this provider supports tool/function calling */
    boolean supportsTools();

    /** Call the LLM API and return a unified response */
    LlmChatResponse chat(LlmChatRequest request, String apiKey, String baseUrl) throws Exception;

    /** Estimate cost in USD for given token usage */
    double estimateCost(String model, int inputTokens, int outputTokens);

    /**
     * Cache-aware cost estimate. Default implementation ignores cache token
     * counts and falls back to the 3-arg path, preserving behaviour for
     * providers (OpenAI-compatible, etc.) that do not yet bill differently
     * for cache writes/reads.
     *
     * <p>Anthropic overrides this to bill cache writes at 1.25x and cache
     * reads at 0.1x of the base input rate.
     *
     * @param model               provider model code
     * @param inputTokens         non-cached input tokens (billed at 1.0x base)
     * @param outputTokens        output tokens
     * @param cacheCreationTokens tokens written to the prompt cache on this call
     * @param cacheReadTokens     tokens served from the prompt cache on this call
     */
    default double estimateCost(String model, int inputTokens, int outputTokens,
                                int cacheCreationTokens, int cacheReadTokens) {
        return estimateCost(model, inputTokens, outputTokens);
    }

    /** Default base URL for this provider */
    String getDefaultBaseUrl();

    /** Default model for this provider */
    String getDefaultModel();
}
