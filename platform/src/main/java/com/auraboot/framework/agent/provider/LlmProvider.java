package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

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

    /**
     * Streaming variant of {@link #chat} (E.1 Phase 1).
     *
     * <p>Returns a {@link Flux} of {@link LlmChunk} values: zero or more
     * incremental delta chunks followed by exactly one terminal chunk where
     * {@code done=true} and {@code aggregateResponse} carries the equivalent
     * of the synchronous {@link #chat} return value.
     *
     * <p><b>Default implementation</b> wraps the synchronous {@link #chat}
     * call in a single-chunk Flux so providers that have not yet implemented
     * real streaming continue to work transparently — callers receive one
     * terminal chunk after the full response arrives. Providers that support
     * a real streaming wire protocol (e.g. Anthropic
     * {@code /v1/messages stream:true}) override this method.
     *
     * <p><b>Important:</b> the synchronous {@link #chat} method does NOT
     * delegate to this stream variant; it remains the canonical sync path so
     * that overriding only {@code streamChat} cannot accidentally introduce a
     * recursive loop. Per spec Q5 there is no fallback from streaming to
     * sync — streaming failures must surface as {@code Flux.error}.
     */
    default Flux<LlmChunk> streamChat(LlmChatRequest request, String apiKey, String baseUrl) {
        return Mono.fromCallable(() -> chat(request, apiKey, baseUrl))
                .flatMapMany(response -> Flux.fromIterable(List.of(LlmChunk.fromFinal(response))));
    }

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
