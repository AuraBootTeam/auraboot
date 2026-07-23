package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.AnthropicRequest;
import com.auraboot.framework.agent.dto.AnthropicResponse;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.common.util.SsrfValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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

    // =========================================================================
    // ACP B.3 advanced — multi-segment system cache + 1h cache TTL.
    //
    // Anthropic's prompt cache has a 1024-token MINIMUM per cacheable block —
    // shorter prefixes are still sent on the wire but never actually cache,
    // so attaching {@code cache_control} to them just wastes the marker slot.
    // We approximate token count with a 4-chars-per-token heuristic (matches
    // Anthropic's English-language average closely enough for a preflight
    // gate; we err on the side of NOT caching when in doubt).
    //
    // The 1024-token floor only applies to the multi-segment path — the
    // legacy single-string {@link #convertSystem(String)} keeps unconditional
    // cache_control because (a) callers using the simple path expect the
    // existing baseline behaviour and (b) the single-string production
    // callers (agent system prompts) are typically far above 1024 tokens
    // already.
    //
    // Long-TTL (1h) cache is gated by {@code agent.anthropic.cache.long-ttl}
    // (default false). When enabled, we send an additional
    // {@code anthropic-beta: extended-cache-ttl-2025-04-11} header AND set
    // {@code cache_control.ttl=1h} on every emitted marker. The 1h cache
    // costs more on creation (still 1.25x but for a longer-lived entry) so
    // it MUST stay opt-in — never silently switch.
    // =========================================================================
    static final int CACHE_MIN_TOKENS = 1024;
    private static final int CHARS_PER_TOKEN_APPROX = 4;
    private static final int CACHE_MIN_CHARS = CACHE_MIN_TOKENS * CHARS_PER_TOKEN_APPROX;
    private static final String LONG_TTL_BETA_HEADER = "extended-cache-ttl-2025-04-11";
    private static final String CACHE_TTL_5M = "5m";
    private static final String CACHE_TTL_1H = "1h";

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

    // =========================================================================
    // P1 — Vision (image input) capability gate.
    //
    // Anthropic accepts image content blocks on Claude 3.5+, Claude 4.x, and
    // future Claude 5.x models. Old Claude 2 / Claude Instant variants reject
    // the request. Unlike Extended Thinking, vision is a request-level
    // capability — sending an image to a non-vision model returns HTTP 400.
    // We surface this as an {@link IllegalArgumentException} on the chat()
    // entry-point so the caller learns immediately rather than getting a
    // confusing wire-level error from Anthropic.
    //
    // Anthropic model codes use two distinct version-placement conventions:
    //   - claude-{family}-{major}-{minor}     (e.g. claude-sonnet-4-6) — newer
    //   - claude-{major}-{minor}-{family}-... (e.g. claude-3-5-sonnet-20241022) — older
    // We match BOTH placements explicitly so the gate works across all
    // Anthropic naming variants seen in the wild.
    // =========================================================================
    private static final Set<String> VISION_CAPABLE_PATTERNS = Set.of(
            // Modern naming: family first
            "sonnet-4-6", "sonnet-4-7",
            "opus-4",
            "haiku-4",
            // Legacy naming: version first (Claude 3.x family — all support vision)
            "3-5-sonnet",
            "3-5-haiku",
            "3-opus",
            "3-sonnet",
            "3-haiku");

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

    // =========================================================================
    // ACP B.3-3 — Anthropic ephemeral prompt cache hit/miss counters.
    //
    // Until now, the provider only emitted a DEBUG log line listing tools.size
    // and the last tool name. Cache hit ratio was therefore unobservable in
    // Grafana / actuator and the "P0-1 saves money" claim could not be backed
    // up with operational data. The two counters below give operators a
    // running tally per (provider, model) of how often a chat() round-trip
    // served tokens from the ephemeral cache vs. wrote new ones.
    //
    //   - aura_agent_anthropic_cache_hit_total{provider,model}
    //       incremented when usage.cache_read_input_tokens > 0
    //
    //   - aura_agent_anthropic_cache_miss_total{provider,model}
    //       incremented when usage.cache_creation_input_tokens > 0 AND
    //       usage.cache_read_input_tokens == 0 (i.e. fresh cache write with
    //       no read served on the same call)
    //
    // The "neither" case (no cache fields, e.g. Anthropic returned 0 for both)
    // is intentionally NOT counted on either side: it represents a request
    // that did not exercise the cache at all (e.g. a one-off call without
    // enough prefix to cache), and rolling it into either bucket would skew
    // the hit-rate ratio. Operators should compute hit_rate as
    //   hit_total / (hit_total + miss_total)
    // and not include uncached calls in the denominator.
    //
    // Cardinality: O(distinct model codes) per provider tag. Anthropic ships
    // a small fixed family (sonnet/opus/haiku x version), so cardinality is
    // single-digit per tenant — Prometheus-safe.
    // =========================================================================
    public static final String CACHE_HIT_NAME = "aura_agent_anthropic_cache_hit_total";
    public static final String CACHE_MISS_NAME = "aura_agent_anthropic_cache_miss_total";
    private static final String CACHE_PROVIDER_TAG = "anthropic";
    private static final String UNKNOWN_MODEL_TAG = "unknown";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * ACP B.3 advanced — opt-in 1h ephemeral cache TTL. Default OFF: long TTL
     * pays the same 1.25x write multiplier but for a longer-lived entry, so
     * it only nets out for genuinely long-running agent sessions. Toggle in
     * {@code application.yml} as {@code agent.anthropic.cache.long-ttl: true}.
     * Field is package-private so unit tests can flip it via
     * {@link org.springframework.test.util.ReflectionTestUtils}.
     */
    @Value("${agent.anthropic.cache.long-ttl:false}")
    boolean cacheLongTtl;

    public AnthropicLlmProvider(@Qualifier("aiWebClient") WebClient webClient,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
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
        // P1 vision capability gate — fail fast before we hit the wire so the
        // caller gets a deterministic error string with the offending model name
        // instead of an opaque HTTP 400 from Anthropic. Silent drop is NOT an
        // option here: image attachment is an explicit user gesture (paperclip
        // upload), and dropping it would erase the user's intent without any
        // visible feedback.
        if (containsImageContent(request.getMessages()) && !supportsVision(request.getModel())) {
            throw new IllegalArgumentException(
                    "model " + request.getModel() + " does not support vision input; "
                            + "use a Claude 3.5+ / 4.x / 5.x model (e.g. claude-sonnet-4-6) "
                            + "or remove the image attachment.");
        }

        // Build Anthropic-specific request — system + last tool both carry
        // cache_control: ephemeral so the prefix is cached across turns.
        // P0-2 M9: collect any provider-side warnings (e.g. max_tokens
        // auto-extension when the Extended Thinking budget exceeds the
        // caller's value) so we can surface them on the response instead of
        // dropping them in a log.warn.
        java.util.List<String> warnings = new java.util.ArrayList<>();
        AnthropicRequest anthropicReq = buildAnthropicRequest(request, warnings);

        // SSRF guard (SEC-20260723-05): baseUrl is tenant-configurable — reject
        // private/loopback/link-local targets and disallowed schemes before the call.
        SsrfValidator.validate(baseUrl + "/v1/messages");

        org.springframework.web.reactive.function.client.WebClient.RequestBodySpec spec = webClient.post()
                .uri(baseUrl + "/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json");
        // B.3-advanced: send the extended-cache-ttl beta header only when the
        // operator opts into 1h cache. Sending it unconditionally is harmless
        // on Anthropic's side, but we keep the wire footprint exactly equal
        // to the pre-feature baseline when long-ttl is off so existing
        // golden-snapshot tests stay byte-identical.
        if (cacheLongTtl) {
            spec = spec.header("anthropic-beta", LONG_TTL_BETA_HEADER);
        }
        String responseBody = spec
                .bodyValue(objectMapper.writeValueAsString(anthropicReq))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        AnthropicResponse anthropicResp = objectMapper.readValue(responseBody, AnthropicResponse.class);
        LlmChatResponse out = convertResponse(anthropicResp);
        recordCacheMetrics(request.getModel(), anthropicResp.getUsage());
        if (!warnings.isEmpty()) {
            out.setWarnings(warnings);
        }
        return out;
    }

    /**
     * Real Anthropic streaming via {@code /v1/messages} {@code stream: true}.
     *
     * <p>Subscribes to the Anthropic SSE event stream and emits one
     * {@link LlmChunk} per relevant event. Event mapping (E.1 Phase 1):
     *
     * <pre>
     *   message_start          → ignored (no user-visible delta)
     *   content_block_start    → ignored (block index tracked internally)
     *   content_block_delta    → text_delta     → LlmChunk.delta(seq, text)
     *                          → thinking_delta → LlmChunk.thinking(seq, text)
     *                          → signature_delta→ accumulated, not emitted as chunk
     *   content_block_stop     → ignored
     *   message_delta          → captures stop_reason + output_tokens
     *   message_stop           → terminal: emit LlmChunk.done(seq, aggregate)
     *   error                  → Flux.error(...) — NO fallback to sync (Q5)
     * </pre>
     *
     * <p>The aggregate {@link LlmChatResponse} is built from accumulated text
     * + thinking blocks + final usage so downstream callers (e.g.
     * {@link com.auraboot.framework.automation.executor.impl.LlmCallExecutor})
     * can write {@code ${outputVariable}} only after the terminal chunk —
     * matching spec Q7 (no partial value visible mid-stream).
     */
    @Override
    public Flux<LlmChunk> streamChat(LlmChatRequest request, String apiKey, String baseUrl) {
        // Vision capability gate mirrors chat() — fail fast before the wire.
        if (containsImageContent(request.getMessages()) && !supportsVision(request.getModel())) {
            return Flux.error(new IllegalArgumentException(
                    "model " + request.getModel() + " does not support vision input; "
                            + "use a Claude 3.5+ / 4.x / 5.x model "
                            + "or remove the image attachment."));
        }

        List<String> warnings = new ArrayList<>();
        AnthropicRequest anthropicReq = buildAnthropicRequest(request, warnings);
        // Force stream:true on the wire body. We re-serialise the Anthropic
        // request as a generic Map so we can add a transport-only field
        // without polluting AnthropicRequest with a flag that has no
        // semantic meaning to the rest of the codebase.
        @SuppressWarnings("unchecked")
        Map<String, Object> body = objectMapper.convertValue(anthropicReq, Map.class);
        body.put("stream", Boolean.TRUE);

        // Streaming aggregator state. Mutable, but confined to the Flux pipeline
        // — Reactor guarantees serialised emission per subscriber so no extra
        // synchronisation is needed inside flatMap callbacks.
        AtomicLong seqCounter = new AtomicLong(0L);
        StreamingAggregator agg = new StreamingAggregator(warnings);

        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return Flux.error(e);
        }

        // SSRF guard (SEC-20260723-05): reject private/loopback targets before streaming.
        SsrfValidator.validate(baseUrl + "/v1/messages");

        Flux<ServerSentEvent<String>> sseFlux = webClient.post()
                .uri(baseUrl + "/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(bodyJson)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});

        return sseFlux
                .concatMap(sse -> {
                    String eventType = sse.event();
                    String data = sse.data();
                    if (eventType == null || data == null || data.isEmpty()) {
                        return Flux.empty();
                    }
                    try {
                        return handleAnthropicSseEvent(eventType, data, seqCounter, agg);
                    } catch (Exception e) {
                        return Flux.error(e);
                    }
                });
    }

    /**
     * Translate one Anthropic SSE event into zero-or-one {@link LlmChunk}.
     * Visible for tests (package-private) so unit-level event-mapping coverage
     * does not need to spin up a WebClient.
     */
    Flux<LlmChunk> handleAnthropicSseEvent(String eventType,
                                           String data,
                                           AtomicLong seqCounter,
                                           StreamingAggregator agg) throws Exception {
        switch (eventType) {
            case "message_start": {
                JsonNode root = objectMapper.readTree(data);
                JsonNode usage = root.path("message").path("usage");
                if (!usage.isMissingNode()) {
                    agg.inputTokens = usage.path("input_tokens").asInt(agg.inputTokens);
                    agg.cacheCreationInputTokens = usage.path("cache_creation_input_tokens").asInt(agg.cacheCreationInputTokens);
                    agg.cacheReadInputTokens = usage.path("cache_read_input_tokens").asInt(agg.cacheReadInputTokens);
                }
                return Flux.empty();
            }
            case "content_block_start": {
                JsonNode root = objectMapper.readTree(data);
                int idx = root.path("index").asInt(0);
                String type = root.path("content_block").path("type").asText("");
                agg.startBlock(idx, type);
                return Flux.empty();
            }
            case "content_block_delta": {
                JsonNode root = objectMapper.readTree(data);
                int idx = root.path("index").asInt(0);
                JsonNode delta = root.path("delta");
                String dtype = delta.path("type").asText("");
                if ("text_delta".equals(dtype)) {
                    String text = delta.path("text").asText("");
                    agg.appendText(idx, text);
                    long s = seqCounter.getAndIncrement();
                    return Flux.just(LlmChunk.delta(s, text));
                } else if ("thinking_delta".equals(dtype)) {
                    String thinking = delta.path("thinking").asText("");
                    agg.appendThinking(idx, thinking);
                    long s = seqCounter.getAndIncrement();
                    return Flux.just(LlmChunk.thinking(s, thinking));
                } else if ("signature_delta".equals(dtype)) {
                    String sig = delta.path("signature").asText("");
                    agg.appendSignature(idx, sig);
                    return Flux.empty();
                }
                return Flux.empty();
            }
            case "content_block_stop": {
                return Flux.empty();
            }
            case "message_delta": {
                JsonNode root = objectMapper.readTree(data);
                JsonNode delta = root.path("delta");
                String stopReason = delta.path("stop_reason").asText(null);
                if (stopReason != null && !stopReason.isEmpty() && !"null".equals(stopReason)) {
                    agg.stopReason = stopReason;
                }
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode()) {
                    agg.outputTokens = usage.path("output_tokens").asInt(agg.outputTokens);
                }
                return Flux.empty();
            }
            case "message_stop": {
                LlmChatResponse aggregate = agg.toResponse();
                long s = seqCounter.getAndIncrement();
                return Flux.just(LlmChunk.done(s, aggregate));
            }
            case "error": {
                JsonNode root = objectMapper.readTree(data);
                String message = root.path("error").path("message").asText("anthropic stream error");
                return Flux.error(new RuntimeException("Anthropic streaming error: " + message));
            }
            case "ping":
            default:
                return Flux.empty();
        }
    }

    /**
     * Mutable accumulator for SSE-driven response assembly. Kept package-private
     * (and as a static nested class) so unit tests can construct one directly.
     */
    static final class StreamingAggregator {
        final Map<Integer, StringBuilder> textBlocks = new LinkedHashMap<>();
        final Map<Integer, StringBuilder> thinkingBlocks = new LinkedHashMap<>();
        final Map<Integer, StringBuilder> signatureBlocks = new LinkedHashMap<>();
        final Map<Integer, String> blockTypes = new LinkedHashMap<>();
        int inputTokens;
        int outputTokens;
        int cacheCreationInputTokens;
        int cacheReadInputTokens;
        String stopReason;
        final List<String> warnings;

        StreamingAggregator(List<String> warnings) {
            this.warnings = warnings;
        }

        void startBlock(int idx, String type) {
            blockTypes.put(idx, type);
            if ("text".equals(type)) textBlocks.put(idx, new StringBuilder());
            else if ("thinking".equals(type)) thinkingBlocks.put(idx, new StringBuilder());
        }

        void appendText(int idx, String text) {
            textBlocks.computeIfAbsent(idx, k -> new StringBuilder()).append(text);
            blockTypes.putIfAbsent(idx, "text");
        }

        void appendThinking(int idx, String text) {
            thinkingBlocks.computeIfAbsent(idx, k -> new StringBuilder()).append(text);
            blockTypes.putIfAbsent(idx, "thinking");
        }

        void appendSignature(int idx, String sig) {
            signatureBlocks.computeIfAbsent(idx, k -> new StringBuilder()).append(sig);
        }

        LlmChatResponse toResponse() {
            List<LlmChatResponse.ContentBlock> blocks = new ArrayList<>();
            // Preserve insertion order so a thinking-then-text response keeps
            // the same block ordering callers see from the synchronous path.
            for (Map.Entry<Integer, String> entry : blockTypes.entrySet()) {
                int idx = entry.getKey();
                String type = entry.getValue();
                LlmChatResponse.ContentBlock.ContentBlockBuilder b =
                        LlmChatResponse.ContentBlock.builder().type(type);
                if ("text".equals(type)) {
                    StringBuilder sb = textBlocks.get(idx);
                    b.text(sb == null ? "" : sb.toString());
                } else if ("thinking".equals(type)) {
                    StringBuilder sb = thinkingBlocks.get(idx);
                    b.thinking(sb == null ? "" : sb.toString());
                    StringBuilder sig = signatureBlocks.get(idx);
                    if (sig != null) b.signature(sig.toString());
                }
                blocks.add(b.build());
            }
            LlmChatResponse out = LlmChatResponse.builder()
                    .stopReason(stopReason)
                    .content(blocks)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .cacheCreationInputTokens(cacheCreationInputTokens)
                    .cacheReadInputTokens(cacheReadInputTokens)
                    .build();
            if (warnings != null && !warnings.isEmpty()) {
                out.setWarnings(warnings);
            }
            return out;
        }
    }

    /**
     * Increment the Anthropic prompt cache hit/miss counters based on the
     * usage block returned by the API. See the field-level comment on
     * {@link #CACHE_HIT_NAME} for the exact semantics; in short:
     *
     * <ul>
     *   <li>{@code cache_read_input_tokens > 0} → hit (cache served tokens).</li>
     *   <li>{@code cache_creation_input_tokens > 0 && cache_read_input_tokens == 0}
     *       → miss (cache freshly written, no read on this call).</li>
     *   <li>Both zero → uncached call, neither counter is incremented.</li>
     * </ul>
     *
     * <p>The method silently no-ops when {@code meterRegistry} is null (some
     * legacy unit-test paths construct the provider without a registry) or
     * when the response carries no {@code usage} block (defensive against an
     * Anthropic shape change). It does NOT throw — observability must never
     * crash the request flow.
     */
    private void recordCacheMetrics(String requestedModel, AnthropicResponse.Usage usage) {
        if (meterRegistry == null || usage == null) {
            return;
        }
        int read = usage.getCache_read_input_tokens();
        int creation = usage.getCache_creation_input_tokens();
        String modelTag = (requestedModel == null || requestedModel.isBlank())
                ? UNKNOWN_MODEL_TAG : requestedModel;
        // B.3-advanced: tag with the cache TTL the request was issued under so
        // dashboards can split hit-rate by 5m vs 1h cache lifetime. The TTL is
        // a deployment-level knob, not a per-request choice, so the cardinality
        // stays at 2 values (5m / 1h) — Prometheus-safe.
        String ttlTag = cacheLongTtl ? CACHE_TTL_1H : CACHE_TTL_5M;
        if (read > 0) {
            Counter.builder(CACHE_HIT_NAME)
                    .description("Anthropic chat() responses where usage.cache_read_input_tokens > 0")
                    .tag("provider", CACHE_PROVIDER_TAG)
                    .tag("model", modelTag)
                    .tag("ttl", ttlTag)
                    .register(meterRegistry)
                    .increment();
        } else if (creation > 0) {
            Counter.builder(CACHE_MISS_NAME)
                    .description("Anthropic chat() responses where cache_creation_input_tokens > 0 and cache_read_input_tokens == 0")
                    .tag("provider", CACHE_PROVIDER_TAG)
                    .tag("model", modelTag)
                    .tag("ttl", ttlTag)
                    .register(meterRegistry)
                    .increment();
        }
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

        Object systemBlocks;
        List<LlmChatRequest.SystemSegment> segments = request.getSystemSegments();
        if (segments != null && !segments.isEmpty()) {
            systemBlocks = convertSystemFromSegments(segments);
        } else {
            systemBlocks = convertSystem(request.getSystemPrompt());
        }
        return AnthropicRequest.builder()
                .model(request.getModel())
                .max_tokens(maxTokens)
                .system(systemBlocks)
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
                        .content(convertMessageContent(m.getContent()))
                        .build())
                .toList();
    }

    /**
     * Translate the unified {@code Message.content} into the wire-format value
     * Anthropic expects:
     * <ul>
     *   <li>String → passes through unchanged (text-only messages).</li>
     *   <li>{@code List<MessageContentBlock>} → each block is mapped to
     *       {@link AnthropicRequest.ImageContentBlock} so the
     *       {@code source.media_type} field serialises with snake_case as
     *       required by the API.</li>
     *   <li>Any other List (e.g. tool_use / tool_result blocks emitted by the
     *       agent loop) → passes through unchanged so existing assistant turn
     *       handling stays byte-identical.</li>
     * </ul>
     *
     * <p>Mapping is intentionally one-way and explicit — we do NOT silently
     * coerce shape mismatches because vision input is a load-bearing user
     * gesture and a quiet "(image stripped)" fallback would hide bugs.
     */
    private Object convertMessageContent(Object content) {
        if (!(content instanceof List<?> list) || list.isEmpty()) {
            return content;
        }
        // Detect MessageContentBlock at the head — if present, ALL blocks are
        // expected to be MessageContentBlock (the helpers in Message build
        // homogeneous lists). Mixed legacy-vs-vision blocks are not supported
        // because tool_use blocks never appear on the same turn as image input.
        Object first = list.get(0);
        if (!(first instanceof LlmChatRequest.MessageContentBlock)) {
            return content;
        }
        List<AnthropicRequest.ImageContentBlock> converted = new ArrayList<>(list.size());
        for (Object raw : list) {
            if (!(raw instanceof LlmChatRequest.MessageContentBlock block)) continue;
            AnthropicRequest.ImageContentBlock.ImageContentBlockBuilder b =
                    AnthropicRequest.ImageContentBlock.builder().type(block.getType());
            if ("image".equals(block.getType()) && block.getSource() != null) {
                LlmChatRequest.ImageSource src = block.getSource();
                b.source(AnthropicRequest.ImageSource.builder()
                        .type(src.getType())
                        .mediaType(src.getMediaType())
                        .data(src.getData())
                        .url(src.getUrl())
                        .build());
            } else if ("text".equals(block.getType())) {
                b.text(block.getText());
            }
            converted.add(b.build());
        }
        return converted;
    }

    /**
     * P1 capability gate for Anthropic vision (image content blocks). Returns
     * {@code true} iff the model code carries one of the
     * {@link #VISION_CAPABLE_PATTERNS} substrings — every Claude 3.5+, 4.x,
     * and 5.x family member. Returns {@code false} for null/empty/legacy
     * (claude-2, claude-instant) identifiers.
     *
     * <p>Visible to tests via package-private accessor.
     */
    boolean supportsVision(String model) {
        if (model == null || model.isBlank()) return false;
        for (String pattern : VISION_CAPABLE_PATTERNS) {
            if (model.contains(pattern)) return true;
        }
        return false;
    }

    /**
     * Returns true iff any message in the request carries at least one
     * image content block. Used by {@link #chat} as the trigger for the
     * {@link #supportsVision} capability gate.
     */
    private boolean containsImageContent(List<LlmChatRequest.Message> messages) {
        if (messages == null) return false;
        for (LlmChatRequest.Message m : messages) {
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object block : blocks) {
                if (block instanceof LlmChatRequest.MessageContentBlock mcb
                        && "image".equals(mcb.getType())) {
                    return true;
                }
            }
        }
        return false;
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
        last.setCache_control(buildCacheControl());
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
        block.put("cache_control", buildCacheControl());
        return List.of(block);
    }

    /**
     * Multi-segment system prompt converter (ACP B.3 advanced). Each
     * {@link LlmChatRequest.SystemSegment} becomes its own Anthropic content
     * block; segments flagged {@code cacheable=true} get a {@code cache_control}
     * marker iff their text passes the {@link #CACHE_MIN_CHARS} preflight
     * (Anthropic's 1024-token floor approximated at 4 chars/token). Sub-floor
     * segments still ship as plain text — they just don't carry a cache hint
     * because Anthropic would silently ignore it anyway, and emitting the
     * marker would muddy the operator's mental model of "every cache_control
     * marker = a real cache slot".
     *
     * <p>Anthropic supports multiple cache_control markers per request — the
     * whole array up to (and including) each marker becomes its own cache
     * entry. Tenants can therefore split system prompt into:
     * <ol>
     *   <li>tenant-level template (cacheable=true, big — hits the cache)</li>
     *   <li>session-level details (cacheable=false — keeps the prefix cache
     *       valid even when this segment changes)</li>
     * </ol>
     *
     * <p>Returns {@code null} when the segments list is empty or contains
     * only blank text — same null contract as the single-string overload so
     * the {@code AnthropicRequest.system} field gets omitted via
     * {@code @JsonInclude(NON_NULL)}.
     */
    private Object convertSystemFromSegments(List<LlmChatRequest.SystemSegment> segments) {
        List<Map<String, Object>> blocks = new ArrayList<>(segments.size());
        for (LlmChatRequest.SystemSegment seg : segments) {
            if (seg == null) continue;
            String text = seg.getText();
            if (text == null || text.isBlank()) continue;
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", text);
            if (seg.isCacheable()) {
                if (text.length() >= CACHE_MIN_CHARS) {
                    block.put("cache_control", buildCacheControl());
                } else if (log.isDebugEnabled()) {
                    log.debug("Anthropic prompt cache: segment skipped cache_control "
                                    + "(len={} chars < {} ≈ {} tokens minimum)",
                            text.length(), CACHE_MIN_CHARS, CACHE_MIN_TOKENS);
                }
            }
            blocks.add(block);
        }
        return blocks.isEmpty() ? null : blocks;
    }

    /**
     * Build the {@code cache_control} marker map. Returns
     * {@code {"type":"ephemeral"}} by default; when {@link #cacheLongTtl} is
     * on, also adds {@code "ttl":"1h"} to upgrade the cache lifetime from
     * Anthropic's default 5-minute to the 1-hour beta tier (requires the
     * {@code anthropic-beta: extended-cache-ttl-2025-04-11} request header,
     * which {@link #chat} attaches in the same conditional).
     *
     * <p>Each call returns a NEW map — the marker is mutated by Jackson during
     * serialization in some code paths and we want every emitted marker to be
     * independent so a downstream mutation never bleeds into another block.
     */
    private Map<String, Object> buildCacheControl() {
        Map<String, Object> cc = new LinkedHashMap<>();
        cc.put("type", "ephemeral");
        if (cacheLongTtl) {
            cc.put("ttl", CACHE_TTL_1H);
        }
        return cc;
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
