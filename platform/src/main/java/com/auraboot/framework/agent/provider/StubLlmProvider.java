package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub LLM provider used as a no-op fallback when no real LLM API key is
 * configured. Returns a fixed deterministic response so AuraBot E2E suites
 * (and any other code path that depends on the chat pipeline being live) can
 * exercise the full request flow without needing real provider credentials.
 *
 * <p>Activation: {@link LlmProviderFactory} routes to this bean whenever the
 * resolved API key for any provider equals
 * {@link #STUB_API_KEY_SENTINEL}, OR when {@code agent.llm.stub-mode=true} is
 * set in {@code application.yml}. Both knobs are intentionally explicit — we
 * never silently swap a real provider for the stub. This is a test affordance,
 * not a production fallback, and the red-line on "no fallback" still holds for
 * real providers (a misconfigured Anthropic key still surfaces as an error).
 *
 * <p>The stub answers every {@link #chat} call with a single text block
 * containing {@code "[stub response]"} and stop_reason {@code "end_turn"}. It
 * does NOT emit tool_use blocks because the AuraBot turn loop interprets
 * tool_use as "call a platform/MCP tool" — emitting a fake tool call here
 * would cascade into either an unknown-tool error or an infinite loop. The
 * stub therefore always declares the turn finished. Suites that rely on
 * tool_use behaviour mock the chat layer directly via Playwright route
 * interception and bypass this provider entirely.
 *
 * <p>Streaming: {@link #streamChat} emits a single delta chunk followed by a
 * terminal {@code done} chunk wrapping the same response. This matches the
 * Anthropic streaming wire shape closely enough that downstream Flux-based
 * aggregators (e.g. {@code LlmCallExecutor}, SSE bridge) treat it identically.
 */
@Slf4j
@Component
public class StubLlmProvider implements LlmProvider {

    /**
     * Sentinel API-key string used by tests / dev environments to opt into the
     * stub provider. Matches the value already baked into
     * {@code docker-compose.acp-validate.override.yml}.
     */
    public static final String STUB_API_KEY_SENTINEL = "stub_key_for_no_llm_paths";

    /** Provider code; matches the conventional {@code stub} identifier. */
    public static final String PROVIDER_CODE = "stub";

    /** Fixed response text returned for every chat request. */
    static final String STUB_RESPONSE_TEXT = "[stub response]";

    @Override
    public String getProviderCode() {
        return PROVIDER_CODE;
    }

    @Override
    public String getDisplayName() {
        return "Stub (no-op LLM)";
    }

    @Override
    public boolean supportsTools() {
        // The stub never emits tool_use blocks, but advertising true here keeps
        // the agent loop's tool-availability checks identical to the real
        // provider path. Callers that inspect supportsTools() use it to decide
        // whether to attach the tools array; attaching it is harmless because
        // the stub ignores the field entirely.
        return true;
    }

    @Override
    public LlmChatResponse chat(LlmChatRequest request, String apiKey, String baseUrl) {
        return buildStubResponse(request);
    }

    @Override
    public Flux<LlmChunk> streamChat(LlmChatRequest request, String apiKey, String baseUrl) {
        LlmChatResponse aggregate = buildStubResponse(request);
        // Emit one delta chunk + one terminal done chunk so downstream
        // aggregators that expect at least one delta before the terminal frame
        // (mirroring Anthropic's wire shape) stay byte-compatible.
        LlmChunk deltaChunk = LlmChunk.delta(0L, STUB_RESPONSE_TEXT);
        LlmChunk doneChunk = LlmChunk.done(1L, aggregate);
        return Flux.just(deltaChunk, doneChunk);
    }

    @Override
    public double estimateCost(String model, int inputTokens, int outputTokens) {
        // Stub responses are free — they never hit a real provider.
        return 0.0;
    }

    @Override
    public String getDefaultBaseUrl() {
        return "stub://local";
    }

    @Override
    public String getDefaultModel() {
        return "stub-model";
    }

    /**
     * Build a deterministic {@link LlmChatResponse} carrying a single text
     * content block. Token counts are estimated from the request size at the
     * 4-chars-per-token approximation Anthropic uses, so cost / quota
     * accounting code that reads {@code inputTokens}/{@code outputTokens}
     * gets non-zero values and behaves like a real call.
     */
    private LlmChatResponse buildStubResponse(LlmChatRequest request) {
        List<LlmChatResponse.ContentBlock> content = new ArrayList<>(1);
        content.add(LlmChatResponse.ContentBlock.builder()
                .type("text")
                .text(STUB_RESPONSE_TEXT)
                .build());
        int inputTokens = estimateInputTokens(request);
        int outputTokens = Math.max(1, STUB_RESPONSE_TEXT.length() / 4);
        return LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(content)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cacheCreationInputTokens(0)
                .cacheReadInputTokens(0)
                .build();
    }

    /**
     * Cheap input-token estimate: sum of system prompt + every message
     * content's String representation, divided by 4. The stub doesn't need
     * this to be accurate; it only needs to be > 0 so quota / cost summaries
     * don't show suspicious zeroes during E2E.
     */
    private int estimateInputTokens(LlmChatRequest request) {
        int chars = 0;
        if (request.getSystemPrompt() != null) {
            chars += request.getSystemPrompt().length();
        }
        if (request.getMessages() != null) {
            for (LlmChatRequest.Message m : request.getMessages()) {
                Object c = m.getContent();
                if (c != null) {
                    chars += c.toString().length();
                }
            }
        }
        // The metadata map ensures static analysis sees both branches as live.
        Map<String, Object> ignored = new LinkedHashMap<>();
        ignored.put("chars", chars);
        return Math.max(1, chars / 4);
    }
}
