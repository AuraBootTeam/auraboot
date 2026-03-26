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

    /** Default base URL for this provider */
    String getDefaultBaseUrl();

    /** Default model for this provider */
    String getDefaultModel();
}
