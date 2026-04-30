package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.AnthropicRequest;
import com.auraboot.framework.agent.dto.AnthropicResponse;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anthropic Claude Messages API provider.
 * Translates unified LlmChatRequest into Anthropic's /v1/messages format.
 *
 * <p>Prompt caching: every request enables ephemeral cache on (a) the system
 * prompt segment and (b) the last tool in the tools array. Anthropic caches
 * the prefix up to the last {@code cache_control} marker, so identical
 * system+tools across turns will hit the cache and be billed at 0.1x the
 * normal input rate. The first request that establishes the cache pays
 * 1.25x for the cached tokens.
 */
@Slf4j
@Component
public class AnthropicLlmProvider implements LlmProvider {

    private static final Map<String, Object> EPHEMERAL_CACHE_CONTROL =
            Map.of("type", "ephemeral");

    /** Anthropic bills tokens written to the ephemeral cache at 1.25x base input. */
    private static final double CACHE_WRITE_MULTIPLIER = 1.25;

    /** Anthropic bills tokens served from the ephemeral cache at 0.10x base input. */
    private static final double CACHE_READ_MULTIPLIER = 0.10;

    /** Pricing tables are quoted per million tokens. */
    private static final double TOKENS_PER_MILLION = 1_000_000.0;

    /** USD per 1M tokens for a given model family. */
    private record AnthropicPricing(double inputPer1M, double outputPer1M) {}

    /**
     * Lookup table keyed by model-family substring. {@link #estimateCost}
     * matches via {@code model.contains(key)} so concrete model codes such as
     * {@code claude-sonnet-4-6} or {@code claude-opus-4-7} resolve to the
     * right family. Sonnet is the default when no key matches.
     */
    private static final Map<String, AnthropicPricing> PRICING_TABLE = Map.of(
            "opus", new AnthropicPricing(15.0, 75.0),
            "sonnet", new AnthropicPricing(3.0, 15.0),
            "haiku", new AnthropicPricing(0.25, 1.25)
    );

    /** Default family when no key matches — keep in sync with {@link #getDefaultModel()}. */
    private static final AnthropicPricing DEFAULT_PRICING = PRICING_TABLE.get("sonnet");

    // =========================================================================
    // Extended Thinking (P0-2) capability gate.
    //
    // Anthropic only accepts the {@code thinking} request field on Sonnet 4.6+,
    // Opus 4.x, and Haiku 4.x. Older models (claude-3-*) reject the request
    // with HTTP 400 if it carries the field, so we filter on a model-substring
    // allow-list. The check is intentionally conservative: anything not
    // recognised is treated as "no thinking" (silent drop, not error) so that
    // future model codes can be added by editing the allow-list without
    // breaking existing requests.
    // =========================================================================
    private static final Set<String> THINKING_CAPABLE_PATTERNS = Set.of(
            "sonnet-4-6", "sonnet-4-7",
            "opus-4",
            "haiku-4");

    /**
     * Headroom Anthropic requires above {@code thinking.budget_tokens}. The API
     * rejects requests where {@code max_tokens <= budget_tokens}; we want a
     * comfortable margin for the model's actual reply on top of the thinking
     * trace.
     */
    private static final int THINKING_HEADROOM_TOKENS = 1024;

    /**
     * Fallback max_tokens to use when the caller's max_tokens is too small
     * given the requested budget. Generous enough that a model can produce a
     * full reply on top of the thinking trace.
     */
    private static final int THINKING_FALLBACK_MAX_TOKENS = 4096;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public AnthropicLlmProvider(@Qualifier("aiWebClient") WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderCode() {
        return "anthropic";
    }

    @Override
    public String getDisplayName() {
        return "Anthropic (Claude)";
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public String getDefaultBaseUrl() {
        return "https://api.anthropic.com";
    }

    @Override
    public String getDefaultModel() {
        return "claude-sonnet-4-6";
    }

    @Override
    public LlmChatResponse chat(LlmChatRequest request, String apiKey, String baseUrl) throws Exception {
        // Build Anthropic-specific request — system + last tool both carry
        // cache_control: ephemeral so the prefix is cached across turns.
        // P0-2 M9: collect any provider-side warnings (e.g. max_tokens
        // auto-extension when the Extended Thinking budget exceeds the
        // caller's value) so we can surface them on the response instead of
        // dropping them in a log.warn.
        java.util.List<String> warnings = new java.util.ArrayList<>();
        AnthropicRequest anthropicReq = buildAnthropicRequest(request, warnings);

        String responseBody = webClient.post()
                .uri(baseUrl + "/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .bodyValue(objectMapper.writeValueAsString(anthropicReq))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        AnthropicResponse anthropicResp = objectMapper.readValue(responseBody, AnthropicResponse.class);
        LlmChatResponse out = convertResponse(anthropicResp);
        if (!warnings.isEmpty()) {
            out.setWarnings(warnings);
        }
        return out;
    }

    @Override
    public double estimateCost(String model, int inputTokens, int outputTokens) {
        // Backward-compatible path: no cache write/read accounting.
        return estimateCost(model, inputTokens, outputTokens, 0, 0);
    }

    /**
     * Cache-aware cost estimate. Anthropic bills cache writes at 1.25x and
     * cache reads at 0.1x the base input rate; output rate is unchanged.
     *
     * @param model               model code (sonnet/opus/haiku)
     * @param inputTokens         non-cached input tokens (billed at 1.0x)
     * @param outputTokens        output tokens
     * @param cacheCreationTokens tokens written to the ephemeral cache (1.25x)
     * @param cacheReadTokens     tokens served from the ephemeral cache (0.1x)
     */
    @Override
    public double estimateCost(String model, int inputTokens, int outputTokens,
                               int cacheCreationTokens, int cacheReadTokens) {
        AnthropicPricing pricing = resolvePricing(model);
        double total = inputTokens * pricing.inputPer1M()
                + cacheCreationTokens * pricing.inputPer1M() * CACHE_WRITE_MULTIPLIER
                + cacheReadTokens * pricing.inputPer1M() * CACHE_READ_MULTIPLIER
                + outputTokens * pricing.outputPer1M();
        return total / TOKENS_PER_MILLION;
    }

    /**
     * Resolve the pricing tier for a concrete model code by matching the
     * first family keyword found in {@link #PRICING_TABLE}. Falls back to
     * {@link #DEFAULT_PRICING} (sonnet) when no key matches or the model is
     * null — this keeps cost estimation safe for unknown / future models
     * rather than throwing.
     */
    private AnthropicPricing resolvePricing(String model) {
        if (model != null) {
            for (Map.Entry<String, AnthropicPricing> entry : PRICING_TABLE.entrySet()) {
                if (model.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return DEFAULT_PRICING;
    }

    // =========================================================================
    // Format conversion: Unified ↔ Anthropic
    // =========================================================================

    /**
     * Translate the unified {@link LlmChatRequest} into the wire-format
     * {@link AnthropicRequest}. Extracted from {@link #chat} so unit tests can
     * verify thinking-block plumbing without spinning up a WebClient.
     *
     * <p>Extended Thinking gating: the {@code thinking} field is added only when
     * (a) the unified request supplies a non-null {@link LlmChatRequest.ThinkingConfig}
     * with {@code enabled=true}, and (b) the model code matches the
     * {@link #THINKING_CAPABLE_PATTERNS} allow-list. Old models silently drop
     * the field — we never error here because callers may have the same agent
     * config target multiple model versions.
     *
     * <p>When thinking is enabled and the caller's {@code maxTokens} is too
     * small for Anthropic's {@code max_tokens > budget_tokens} requirement, we
     * auto-extend to {@code budget + THINKING_FALLBACK_MAX_TOKENS} and emit a
     * warning log. The alternative (HTTP 400) would silently break callers
     * that did not anticipate the headroom rule.
     */
    AnthropicRequest buildAnthropicRequest(LlmChatRequest request) {
        // Backward-compatible overload — discards any warnings. Used by tests
        // that only need the wire shape; the production path through
        // {@link #chat} uses the warnings-collecting overload below so the
        // surface response can carry the auto-extension message back to the
        // caller (P0-2 M9).
        return buildAnthropicRequest(request, null);
    }

    AnthropicRequest buildAnthropicRequest(LlmChatRequest request, java.util.List<String> warningsOut) {
        int maxTokens = request.getMaxTokens();
        AnthropicRequest.Thinking thinkingBlock = null;

        LlmChatRequest.ThinkingConfig requested = request.getThinking();
        if (requested != null && requested.isEnabled() && supportsThinking(request.getModel())) {
            int budget = requested.getBudgetTokens();
            if (budget <= 0) {
                budget = 10_000; // mirror LlmChatRequest.ThinkingConfig default
            }
            if (maxTokens < budget + THINKING_HEADROOM_TOKENS) {
                int adjusted = budget + THINKING_FALLBACK_MAX_TOKENS;
                String msg = String.format(
                        "Extended Thinking budget (%d) requires max_tokens >= budget + %d; "
                                + "caller passed max_tokens=%d for model=%s, auto-extended to %d. "
                                + "Pass max_tokens >= %d explicitly to silence this warning.",
                        budget, THINKING_HEADROOM_TOKENS, request.getMaxTokens(), request.getModel(),
                        adjusted, budget + THINKING_HEADROOM_TOKENS);
                log.warn(msg);
                if (warningsOut != null) {
                    warningsOut.add(msg);
                }
                maxTokens = adjusted;
            }
            thinkingBlock = AnthropicRequest.Thinking.builder()
                    .type("enabled")
                    .budget_tokens(budget)
                    .build();
        }

        return AnthropicRequest.builder()
                .model(request.getModel())
                .max_tokens(maxTokens)
                .system(convertSystem(request.getSystemPrompt()))
                .messages(convertMessages(request.getMessages()))
                .tools(convertTools(request.getTools()))
                .thinking(thinkingBlock)
                .build();
    }

    /**
     * Capability gate for Anthropic Extended Thinking. Returns {@code true}
     * iff the model code carries one of the {@link #THINKING_CAPABLE_PATTERNS}
     * substrings — i.e. Sonnet 4.6+, Opus 4.x, or Haiku 4.x. Returns
     * {@code false} for null/empty/legacy/non-Anthropic identifiers, so an
     * accidental call from another provider is a silent no-op.
     */
    boolean supportsThinking(String model) {
        if (model == null || model.isBlank()) return false;
        for (String pattern : THINKING_CAPABLE_PATTERNS) {
            if (model.contains(pattern)) return true;
        }
        return false;
    }

    private List<AnthropicRequest.Message> convertMessages(List<LlmChatRequest.Message> messages) {
        if (messages == null) return List.of();
        return messages.stream()
                .map(m -> AnthropicRequest.Message.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .build())
                .toList();
    }

    private List<AnthropicRequest.Tool> convertTools(List<LlmChatRequest.Tool> tools) {
        if (tools == null || tools.isEmpty()) return null;
        List<AnthropicRequest.Tool> converted = new ArrayList<>(tools.size());
        for (LlmChatRequest.Tool t : tools) {
            converted.add(AnthropicRequest.Tool.builder()
                    .name(t.getName())
                    .description(t.getDescription())
                    .input_schema(t.getInputSchema())
                    .build());
        }
        // Anthropic caches the prefix up to (and including) the last
        // cache_control marker, so we mark only the LAST tool. This caches the
        // entire tools array as a single unit.
        AnthropicRequest.Tool last = converted.get(converted.size() - 1);
        last.setCache_control(new HashMap<>(EPHEMERAL_CACHE_CONTROL));
        if (log.isDebugEnabled()) {
            log.debug("Anthropic prompt cache: tools.size={}, lastTool={}",
                    converted.size(), last.getName());
        }
        return converted;
    }

    /**
     * Convert the unified system prompt string into Anthropic's
     * list-of-content-blocks form, with an ephemeral cache_control marker
     * attached so the system segment is cached across turns.
     *
     * @return null when prompt is blank, otherwise a single-element list of
     *         content blocks ready to be serialized as the {@code system} field.
     */
    private Object convertSystem(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return null;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", systemPrompt);
        block.put("cache_control", new HashMap<>(EPHEMERAL_CACHE_CONTROL));
        return List.of(block);
    }

    private LlmChatResponse convertResponse(AnthropicResponse resp) {
        List<LlmChatResponse.ContentBlock> blocks = new ArrayList<>();
        if (resp.getContent() != null) {
            for (AnthropicResponse.ContentBlock b : resp.getContent()) {
                blocks.add(LlmChatResponse.ContentBlock.builder()
                        .type(b.getType())
                        .text(b.getText())
                        .id(b.getId())
                        .name(b.getName())
                        .input(b.getInput())
                        // Anthropic Extended Thinking: type="thinking" carries
                        // the prose chain-of-thought + an opaque signature.
                        // Both fields are null for non-thinking blocks.
                        .thinking(b.getThinking())
                        .signature(b.getSignature())
                        .build());
            }
        }
        AnthropicResponse.Usage usage = resp.getUsage();
        return LlmChatResponse.builder()
                .stopReason(resp.getStop_reason())
                .content(blocks)
                .inputTokens(usage != null ? usage.getInput_tokens() : 0)
                .outputTokens(usage != null ? usage.getOutput_tokens() : 0)
                .cacheCreationInputTokens(usage != null ? usage.getCache_creation_input_tokens() : 0)
                .cacheReadInputTokens(usage != null ? usage.getCache_read_input_tokens() : 0)
                .build();
    }
}
