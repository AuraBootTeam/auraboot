package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.common.util.SsrfValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI-compatible Chat Completions API provider.
 * Works with OpenAI, DeepSeek, Qianwen (DashScope), Zhipu, Moonshot, and any
 * provider that implements the OpenAI Chat Completions API format.
 *
 * Registered as the "openai" provider but also handles openai-compatible providers
 * via the LlmProviderFactory (which maps provider codes to this implementation).
 */
@Slf4j
@Component
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmProvider(@Qualifier("aiWebClient") WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderCode() {
        return "openai";
    }

    @Override
    public String getDisplayName() {
        return "OpenAI";
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public String getDefaultBaseUrl() {
        return "https://api.openai.com";
    }

    @Override
    public String getDefaultModel() {
        return "gpt-4o";
    }

    @Override
    @SuppressWarnings("unchecked")
    public LlmChatResponse chat(LlmChatRequest request, String apiKey, String baseUrl) throws Exception {
        // Anthropic Extended Thinking is intentionally NOT mapped here.
        // OpenAI o1/o3 reasoning_effort lives in a different request shape and
        // is out of scope for P0-2 — see plan §5. Drop the field with a debug
        // log so noisy callers can observe the skip without polluting INFO.
        if (request.getThinking() != null && request.getThinking().isEnabled() && log.isDebugEnabled()) {
            log.debug("OpenAI-compatible provider does not honour LlmChatRequest.thinking; "
                    + "dropping for model={}", request.getModel());
        }

        // Vision is a per-model capability, not a per-provider one: the same OpenAI-compatible
        // endpoint serves both blind and sighted models. Refuse rather than silently drop the image
        // or fabricate "[image]" text — either would erase the caller's intent and produce a
        // confident answer about a picture the model never saw.
        if (containsImageContent(request.getMessages()) && !supportsVision(request.getModel())) {
            throw new IllegalArgumentException(
                    "model '" + request.getModel() + "' does not accept image input. "
                            + "Use a vision-capable model (e.g. qwen-vl-max, gpt-4o) or Anthropic Claude.");
        }

        Map<String, Object> body = buildOpenAiRequestBody(request);

        String normalizedBase = normalizeBaseUrl(baseUrl);

        // SSRF guard: baseUrl is tenant-configurable (CloudConfig) — reject private/
        // loopback/link-local targets and disallowed schemes before the call
        // (throws IllegalArgumentException on a blocked target; SEC-20260723-05).
        SsrfValidator.validate(normalizedBase + "/v1/chat/completions");

        String responseBody;
        try {
            responseBody = webClient.post()
                    .uri(normalizedBase + "/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("content-type", "application/json")
                    .bodyValue(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException wce) {
            // Surface the provider's error body — a bare "400 Bad Request" hides the
            // actual reason (e.g. an invalid tool name or schema).
            log.error("LLM provider request failed: model={} status={} body={}",
                    request.getModel(), wce.getStatusCode(), wce.getResponseBodyAsString());
            throw wce;
        }

        Map<String, Object> resp = objectMapper.readValue(responseBody, Map.class);
        return convertResponse(resp, buildToolNameReverseMap(request));
    }

    // =========================================================================
    // Real streaming (IMPL-02) — token-by-token SSE for OpenAI-compatible providers
    // =========================================================================

    /**
     * Real streaming via OpenAI-compatible {@code /v1/chat/completions}
     * {@code stream:true}. Consumes the {@code data:}-only SSE the wire emits
     * (OpenAI, DeepSeek, Qianwen, Zhipu, Moonshot, …) and forwards each
     * {@code choices[0].delta.content} fragment as an in-progress
     * {@link LlmChunk} delta, then emits exactly one terminal chunk carrying the
     * aggregated {@link LlmChatResponse}.
     *
     * <p>Before this override the provider fell back to {@link LlmProvider}'s
     * default, which blocks on {@link #chat} and emits a single terminal chunk —
     * no per-token stream, SSE back-pressure dead. Every OpenAI-compatible model
     * (DeepSeek included) was pseudo-streamed on the no-tool chat path.
     *
     * <p>The aggregate is rebuilt through the same {@link #convertResponse} used
     * by the sync path, so tool-call name reverse-mapping, stop-reason
     * normalisation and usage extraction stay identical across the two paths.
     * Per {@link LlmProvider} spec Q5 there is no fallback to sync — streaming
     * failures surface as {@link Flux#error}.
     */
    @Override
    public Flux<LlmChunk> streamChat(LlmChatRequest request, String apiKey, String baseUrl) {
        // Vision capability gate mirrors chat() — fail fast before the wire.
        if (containsImageContent(request.getMessages()) && !supportsVision(request.getModel())) {
            return Flux.error(new IllegalArgumentException(
                    "model '" + request.getModel() + "' does not accept image input. "
                            + "Use a vision-capable model (e.g. qwen-vl-max, gpt-4o) or Anthropic Claude."));
        }
        if (request.getThinking() != null && request.getThinking().isEnabled() && log.isDebugEnabled()) {
            log.debug("OpenAI-compatible provider does not honour LlmChatRequest.thinking; "
                    + "dropping for model={}", request.getModel());
        }

        String bodyJson;
        try {
            Map<String, Object> body = buildOpenAiRequestBody(request);
            body.put("stream", Boolean.TRUE);
            // Ask for a trailing usage-only chunk. OpenAI/DeepSeek honour this and
            // emit final token counts after the content; providers that ignore it
            // simply omit usage and we report 0 — same as the sync path when absent.
            body.put("stream_options", Map.of("include_usage", Boolean.TRUE));
            bodyJson = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return Flux.error(e);
        }

        String url = normalizeBaseUrl(baseUrl) + "/v1/chat/completions";
        // SSRF guard (SEC-20260723-05): reject private/loopback targets before streaming.
        SsrfValidator.validate(url);
        Map<String, String> toolNameReverse = buildToolNameReverseMap(request);
        AtomicLong seq = new AtomicLong(0L);
        OpenAiStreamAggregator agg = new OpenAiStreamAggregator();

        Flux<ServerSentEvent<String>> sseFlux = webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("content-type", "application/json")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(bodyJson)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});

        return sseFlux
                .concatMap(sse -> {
                    String data = sse.data();
                    // OpenAI closes with a literal `data: [DONE]` sentinel and
                    // interleaves keep-alive blanks; neither carries a delta.
                    if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
                        return Flux.empty();
                    }
                    try {
                        return handleOpenAiSseData(data, seq, agg);
                    } catch (Exception e) {
                        return Flux.error(e);
                    }
                })
                // Terminal chunk on successful completion (whether or not a [DONE]
                // sentinel arrived). concatWith is skipped on Flux.error, so a
                // mid-stream failure never produces a bogus aggregate.
                .concatWith(Flux.defer(() ->
                        Flux.just(LlmChunk.done(seq.getAndIncrement(),
                                convertResponse(agg.toResponseMap(), toolNameReverse)))));
    }

    /**
     * Translate one OpenAI-compatible SSE {@code data:} payload into zero-or-more
     * {@link LlmChunk} deltas, accumulating tool-call fragments / usage /
     * finish_reason into {@code agg} for the terminal chunk. Package-private so
     * unit tests can replay recorded frames without a WebClient.
     */
    Flux<LlmChunk> handleOpenAiSseData(String data, AtomicLong seq, OpenAiStreamAggregator agg) throws Exception {
        JsonNode root = objectMapper.readTree(data);

        // Usage arrives on the finishing chunk or, with stream_options, in a
        // trailing choices-empty chunk. Capture whenever present.
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            agg.promptTokens = usage.path("prompt_tokens").asInt(agg.promptTokens);
            agg.completionTokens = usage.path("completion_tokens").asInt(agg.completionTokens);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return Flux.empty();
        }
        JsonNode choice = choices.get(0);
        String finishReason = choice.path("finish_reason").asText(null);
        if (finishReason != null && !finishReason.isEmpty() && !"null".equals(finishReason)) {
            agg.finishReason = finishReason;
        }

        JsonNode delta = choice.path("delta");
        List<LlmChunk> out = new ArrayList<>();

        // DeepSeek-reasoner streams chain-of-thought in reasoning_content; surface it
        // as a thinking delta. The sync convertResponse ignores it, so it never leaks
        // into the visible answer text — the aggregate below matches that.
        JsonNode reasoning = delta.path("reasoning_content");
        if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
            out.add(LlmChunk.thinking(seq.getAndIncrement(), reasoning.asText()));
        }

        JsonNode content = delta.path("content");
        if (content.isTextual() && !content.asText().isEmpty()) {
            String text = content.asText();
            agg.text.append(text);
            out.add(LlmChunk.delta(seq.getAndIncrement(), text));
        }

        // Tool calls stream incrementally: id/name on the first fragment for an
        // index, arguments concatenated across the following fragments.
        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                int idx = tc.path("index").asInt(0);
                OpenAiStreamAggregator.ToolCallAcc acc = agg.toolCall(idx);
                if (tc.hasNonNull("id")) {
                    acc.id = tc.path("id").asText();
                }
                JsonNode fn = tc.path("function");
                if (fn.hasNonNull("name")) {
                    acc.name = fn.path("name").asText();
                }
                if (fn.hasNonNull("arguments")) {
                    acc.arguments.append(fn.path("arguments").asText());
                }
            }
        }

        return out.isEmpty() ? Flux.empty() : Flux.fromIterable(out);
    }

    /**
     * Maps each tool's sanitized (wire) name back to its original name, so a tool_call
     * the model returns (by the sanitized name) is dispatched against the real command
     * code. Built from the request tools — the only names the model can call.
     */
    private static Map<String, String> buildToolNameReverseMap(LlmChatRequest request) {
        Map<String, String> reverse = new LinkedHashMap<>();
        if (request.getTools() != null) {
            for (LlmChatRequest.Tool t : request.getTools()) {
                if (t.getName() != null) {
                    reverse.putIfAbsent(sanitizeToolName(t.getName()), t.getName());
                }
            }
        }
        return reverse;
    }

    /**
     * Sanitizes a tool/function name to the OpenAI-compatible pattern
     * {@code ^[a-zA-Z0-9_-]+$} by replacing every other character (notably the ':' in
     * command codes) with '_'. Deterministic, so the request and history agree.
     */
    static String sanitizeToolName(String name) {
        return name == null ? null : name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * Ensures a tool's {@code parameters} is a valid JSON Schema object. OpenAI/DeepSeek
     * reject a null/empty/type-less schema ("schema must be 'type: object'"). An empty or
     * null schema becomes {@code {type:object, properties:{}}}; a non-empty schema missing
     * {@code type} gets {@code type:object} added while preserving its other keys.
     */
    static Map<String, Object> normalizeToolParameters(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        if (!schema.containsKey("type")) {
            Map<String, Object> copy = new LinkedHashMap<>(schema);
            copy.put("type", "object");
            copy.putIfAbsent("properties", Map.of());
            return copy;
        }
        return schema;
    }

    Map<String, Object> buildOpenAiRequestBody(LlmChatRequest request) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("max_tokens", request.getMaxTokens());

        // Messages: system prompt as first message
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));
        }

        // Convert unified messages to OpenAI format. A single tool-result message can expand to
        // multiple role:tool messages (one per tool_call_id), so flatten rather than 1:1 add.
        if (request.getMessages() != null) {
            for (LlmChatRequest.Message msg : request.getMessages()) {
                messages.addAll(convertMessageToOpenAiMessages(msg));
            }
        }
        body.put("messages", messages);

        if (request.getResponseFormat() != null && !request.getResponseFormat().isBlank()) {
            if (!"json_object".equals(request.getResponseFormat())) {
                throw new IllegalArgumentException(
                        "Unsupported OpenAI-compatible response format: " + request.getResponseFormat());
            }
            body.put("response_format", Map.of("type", "json_object"));
        }

        // Tools
        if (request.getTools() != null && !request.getTools().isEmpty() && !isToolUnsupportedProvider(request.getModel())) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (LlmChatRequest.Tool t : request.getTools()) {
                if (t.getNativeToolConfig() != null) {
                    // LLM-native tool: pass config directly (e.g. {"type": "web_search_preview"})
                    tools.add(t.getNativeToolConfig());
                } else {
                    // Standard function tool. OpenAI-compatible APIs (OpenAI, DeepSeek, …)
                    // require the function name to match ^[a-zA-Z0-9_-]+$ — but AuraBoot
                    // command tools are named with command codes like
                    // "sales_lead_crm:create_sales_lead" (a ':'), which DeepSeek rejects
                    // with a 400. Sanitize on the wire; convertResponse maps the name back.
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", sanitizeToolName(t.getName()));
                    fn.put("description", t.getDescription());
                    // OpenAI/DeepSeek require parameters to be a JSON Schema of type:"object".
                    // A tool with an empty or type-less inputSchema (e.g. {}) makes DeepSeek
                    // 400 ("schema must be 'type: object', got 'type: null'"), so normalize.
                    fn.put("parameters", normalizeToolParameters(t.getInputSchema()));
                    tools.add(Map.<String, Object>of("type", "function", "function", fn));
                }
            }
            body.put("tools", tools);
            String toolChoice = request.getToolChoice();
            if (toolChoice != null && !toolChoice.isBlank()) {
                body.put("tool_choice", toolChoice);
            }
        } else if (request.getTools() != null && !request.getTools().isEmpty()) {
            if ("required".equals(request.getToolChoice())) {
                throw new IllegalArgumentException(
                        "tool_choice=required requested but tool payload is disabled for model: " + request.getModel());
            }
            log.debug("Skipping tool payload for model '{}' because provider compatibility is disabled", request.getModel());
        }
        return body;
    }

    @Override
    public double estimateCost(String model, int inputTokens, int outputTokens) {
        double inputRate;
        double outputRate;
        final String m = model == null ? null : model.toLowerCase();
        if (m == null) {
            inputRate = 2.5; outputRate = 10.0;
        } else if (m.contains("gpt-4o-mini")) {
            inputRate = 0.15; outputRate = 0.6;
        } else if (m.contains("gpt-4o")) {
            inputRate = 2.5; outputRate = 10.0;
        } else if (m.contains("gpt-4.1")) {
            inputRate = 2.0; outputRate = 8.0;
        } else if (m.contains("o1") || m.contains("o3") || m.contains("o4-mini")) {
            inputRate = 1.1; outputRate = 4.4;
        } else if (m.contains("deepseek")) {
            inputRate = 0.27; outputRate = 1.1;
        } else if (m.contains("qwen")) {
            inputRate = 0.3; outputRate = 0.6;
        } else if (m.contains("glm")) {
            inputRate = 0.5; outputRate = 0.5;
        } else if (m.contains("minimax") || m.contains("abab")) {
            inputRate = 1.0; outputRate = 4.0; // MiniMax-M2.5 approximate rates
        } else if (m.contains("sonar-pro")) {
            inputRate = 3.0; outputRate = 15.0;
        } else if (m.contains("sonar")) {
            inputRate = 1.0; outputRate = 1.0;
        } else if (m.contains("llama") || m.contains("mistral") || m.contains("phi")
                || m.contains("gemma") || m.startsWith("local-")) {
            inputRate = 0.0; outputRate = 0.0; // Local models — no API cost
        } else {
            inputRate = 2.5; outputRate = 10.0;
        }
        return (inputTokens * inputRate + outputTokens * outputRate) / 1_000_000.0;
    }

    /** Strip a trailing {@code /v1} or {@code /v1/} so we never post to {@code /v1/v1/...}. */
    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        if (baseUrl.endsWith("/v1/")) {
            return baseUrl.substring(0, baseUrl.length() - 4);
        }
        if (baseUrl.endsWith("/v1")) {
            return baseUrl.substring(0, baseUrl.length() - 3);
        }
        return baseUrl;
    }

    private boolean isToolUnsupportedProvider(String model) {
        // MiniMax-M2.5+ supports OpenAI-compatible function calling.
        // Only disable for truly unsupported providers if discovered later.
        return false;
    }

    /**
     * P1 vision pre-check. Returns true iff any user message carries a
     * {@code MessageContentBlock} with {@code type=image}. This provider
     * rejects such requests outright rather than dropping the image silently —
     * see the {@link #chat} guard for rationale.
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

    /**
     * Models on OpenAI-compatible endpoints that accept image input.
     *
     * <p>Matched as substrings so a dated or suffixed release (qwen-vl-max-0809, gpt-4o-2024-08-06)
     * is recognised without an entry per build. Anything not listed is refused — a model that
     * silently ignores the image and answers anyway is worse than an error.
     *
     * <p>{@code qwen-vl} is verified against the live DashScope compatible-mode endpoint: it accepts
     * standard OpenAI {@code image_url} blocks. (An earlier comment here claimed qwen-vl needed a
     * different field shape; that is true of DashScope's *native* API, not the compatible one.)
     */
    private static final Set<String> VISION_CAPABLE_MODEL_PATTERNS = Set.of(
            "qwen-vl",      // DashScope — live-verified
            "qwen2-vl",
            "qwen2.5-vl",
            "gpt-4o",
            "gpt-4.1",
            "gpt-4-turbo",
            "glm-4v",
            "step-1v",
            "moonshot-v1-vision");

    /** Visible to tests. */
    boolean supportsVision(String model) {
        if (model == null || model.isBlank()) return false;
        String m = model.toLowerCase(Locale.ROOT);
        for (String pattern : VISION_CAPABLE_MODEL_PATTERNS) {
            if (m.contains(pattern)) return true;
        }
        return false;
    }

    /**
     * Translate our internal (Anthropic-shaped) image block into the OpenAI wire shape:
     * {@code {"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}}.
     *
     * <p>The two APIs disagree on how to carry an image, and the internal DTO follows Anthropic.
     * Without this translation the block falls through to {@code String.valueOf(content)} and the
     * provider posts a stringified Java object — which is why image input used to be refused
     * outright rather than converted badly.
     */
    private Map<String, Object> toOpenAiContentBlock(Map<String, Object> block) {
        String type = (String) block.get("type");
        if ("text".equals(type)) {
            return Map.of("type", "text", "text", String.valueOf(block.get("text")));
        }
        if (!"image".equals(type)) {
            return null;
        }

        Object rawSource = block.get("source");
        Map<?, ?> source = rawSource instanceof Map<?, ?> m ? m
                : objectMapper.convertValue(rawSource, Map.class);

        // ImageSource carries either inline bytes or a remote URL. OpenAI takes both through the
        // same image_url field — the inline case just wears a data: URI.
        Object url = source.get("url");
        if (url != null && !String.valueOf(url).isBlank()) {
            return Map.of("type", "image_url", "image_url", Map.of("url", String.valueOf(url)));
        }

        Object mediaType = source.get("mediaType") != null
                ? source.get("mediaType") : source.get("media_type");
        Object data = source.get("data");
        if (mediaType == null || data == null) {
            throw new IllegalArgumentException(
                    "image block has neither a url nor base64 data — refusing to send a broken image");
        }

        return Map.of(
                "type", "image_url",
                "image_url", Map.of("url", "data:" + mediaType + ";base64," + data));
    }

    // =========================================================================
    // Format conversion
    // =========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertMessageToOpenAi(LlmChatRequest.Message msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", msg.getRole());

        Object content = msg.getContent();
        if (content instanceof String) {
            result.put("content", content);
            return result;
        }

        // Content is a list of blocks (tool_use / tool_result / text)
        if (content instanceof List<?> blocks) {
            // Check if this is an assistant message with tool_use blocks
            if ("assistant".equals(msg.getRole())) {
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                StringBuilder textParts = new StringBuilder();

                for (Object block : blocks) {
                    Map<String, Object> bMap = toBlockMap(block);
                    if (bMap != null) {
                        String type = (String) bMap.get("type");
                        if ("tool_use".equals(type)) {
                            Map<String, Object> tc = new LinkedHashMap<>();
                            tc.put("id", bMap.get("id"));
                            tc.put("type", "function");
                            Map<String, Object> fn = new LinkedHashMap<>();
                            // Same sanitization as the tools array (above) so prior-round
                            // assistant tool_calls in the history match the function names.
                            fn.put("name", sanitizeToolName((String) bMap.get("name")));
                            try {
                                fn.put("arguments", objectMapper.writeValueAsString(bMap.get("input")));
                            } catch (Exception e) {
                                fn.put("arguments", "{}");
                            }
                            tc.put("function", fn);
                            toolCalls.add(tc);
                        } else if ("text".equals(type)) {
                            textParts.append(bMap.get("text"));
                        }
                    }
                }

                result.put("content", textParts.length() > 0 ? textParts.toString() : null);
                if (!toolCalls.isEmpty()) {
                    result.put("tool_calls", toolCalls);
                }
                return result;
            }

            // User message with tool_result blocks → a tool message. This single-message variant
            // returns the FIRST tool result; full multi-tool splitting is done by
            // convertMessageToOpenAiMessages (which buildOpenAiRequestBody uses).
            for (Object block : blocks) {
                Map<String, Object> bMap = toBlockMap(block);
                if (bMap != null && "tool_result".equals(bMap.get("type"))) {
                    return toToolMessage(bMap);
                }
            }

            // Multimodal user message: emit OpenAI's content array. The chat() guard has already
            // established the model can see, so anything reaching here is safe to convert.
            List<Map<String, Object>> parts = new ArrayList<>();
            for (Object block : blocks) {
                Map<String, Object> bMap = toBlockMap(block);
                if (bMap == null) continue;
                Map<String, Object> part = toOpenAiContentBlock(bMap);
                if (part != null) {
                    parts.add(part);
                }
            }
            if (parts.stream().anyMatch(p -> "image_url".equals(p.get("type")))) {
                result.put("content", parts);
                return result;
            }
        }

        result.put("content", String.valueOf(content));
        return result;
    }

    /**
     * Convert one unified message into one or more OpenAI-format messages.
     *
     * <p>The agent batches every tool result of a round into a single Anthropic-style
     * {@code role:"user"} message. OpenAI-compatible providers (OpenAI, DeepSeek, …) require a
     * separate {@code role:"tool"} message per {@code tool_call_id}; emitting only the first makes
     * DeepSeek reject the request with "An assistant message with 'tool_calls' must be followed by
     * tool messages responding to each 'tool_call_id'". So tool-result messages are split here;
     * every other message maps 1:1.
     */
    List<Map<String, Object>> convertMessageToOpenAiMessages(LlmChatRequest.Message msg) {
        Object content = msg.getContent();
        if (!"assistant".equals(msg.getRole()) && content instanceof List<?> blocks) {
            List<Map<String, Object>> toolMessages = new ArrayList<>();
            for (Object block : blocks) {
                Map<String, Object> bMap = toBlockMap(block);
                if (bMap != null && "tool_result".equals(bMap.get("type"))) {
                    toolMessages.add(toToolMessage(bMap));
                }
            }
            if (!toolMessages.isEmpty()) {
                return toolMessages;
            }
        }
        return List.of(convertMessageToOpenAi(msg));
    }

    /** Build one OpenAI {@code role:"tool"} message from a tool_result block map. */
    private Map<String, Object> toToolMessage(Map<String, Object> bMap) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "tool");
        Object toolUseId = bMap.get("tool_use_id") != null ? bMap.get("tool_use_id") : bMap.get("toolUseId");
        result.put("tool_call_id", toolUseId);
        Object toolContent = bMap.get("content") != null ? bMap.get("content") : bMap.get("result");
        result.put("content", serializeToolContent(toolContent));
        return result;
    }

    private String serializeToolContent(Object toolContent) {
        if (toolContent == null) {
            return "";
        }
        if (toolContent instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(toolContent);
        } catch (Exception ignored) {
            return String.valueOf(toolContent);
        }
    }

    private Map<String, Object> toBlockMap(Object block) {
        if (block instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        if (block instanceof LlmChatRequest.ContentBlock cb) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", cb.getType());
            result.put("text", cb.getText());
            result.put("id", cb.getId());
            result.put("name", cb.getName());
            result.put("input", cb.getInput());
            result.put("tool_use_id", cb.getToolUseId());
            result.put("toolUseId", cb.getToolUseId());
            result.put("result", cb.getResult());
            return result;
        }
        // Multimodal blocks are a different class from tool blocks — text/image, no tool fields.
        // Missing this branch is why image content used to reach the wire as a Java toString.
        if (block instanceof LlmChatRequest.MessageContentBlock mcb) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", mcb.getType());
            result.put("text", mcb.getText());
            result.put("source", mcb.getSource());
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private LlmChatResponse convertResponse(Map<String, Object> resp, Map<String, String> toolNameReverse) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty()) {
            return LlmChatResponse.builder()
                    .stopReason("end_turn")
                    .content(List.of())
                    .build();
        }

        Map<String, Object> choice = choices.get(0);
        String finishReason = (String) choice.get("finish_reason");
        Map<String, Object> message = (Map<String, Object>) choice.get("message");

        List<LlmChatResponse.ContentBlock> content = new ArrayList<>();

        // Text content
        String textContent = (String) message.get("content");
        if (textContent != null && !textContent.isBlank()) {
            content.add(LlmChatResponse.ContentBlock.builder()
                    .type("text")
                    .text(textContent)
                    .build());
        }

        // Tool calls
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
        if (toolCalls != null) {
            for (Map<String, Object> tc : toolCalls) {
                Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                Map<String, Object> args = Map.of();
                try {
                    String argsStr = (String) fn.get("arguments");
                    if (argsStr != null && !argsStr.isBlank()) {
                        args = new ObjectMapper().readValue(argsStr, Map.class);
                    }
                } catch (Exception ignored) {}

                String wireName = (String) fn.get("name");
                content.add(LlmChatResponse.ContentBlock.builder()
                        .type("tool_use")
                        .id((String) tc.get("id"))
                        // Map the sanitized wire name back to the original command code
                        // so the tool-loop dispatches against the real tool.
                        .name(toolNameReverse.getOrDefault(wireName, wireName))
                        .input(args)
                        .build());
            }
        }

        // Normalize stop reason
        String stopReason;
        if ("tool_calls".equals(finishReason)) {
            stopReason = "tool_use";
        } else if ("length".equals(finishReason)) {
            stopReason = "max_tokens";
        } else {
            stopReason = "end_turn";
        }

        // Usage
        int inputTokens = 0;
        int outputTokens = 0;
        Map<String, Object> usage = (Map<String, Object>) resp.get("usage");
        if (usage != null) {
            inputTokens = ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue();
            outputTokens = ((Number) usage.getOrDefault("completion_tokens", 0)).intValue();
        }

        return LlmChatResponse.builder()
                .stopReason(stopReason)
                .content(content)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .build();
    }

    /**
     * Mutable accumulator for OpenAI-compatible SSE streaming. Confined to a
     * single Flux pipeline (Reactor serialises emission per subscriber), so no
     * extra synchronisation is needed. {@link #toResponseMap()} rebuilds the
     * exact {@code /chat/completions} response shape {@link #convertResponse}
     * expects, so the streaming aggregate and the sync path share one converter.
     */
    static final class OpenAiStreamAggregator {
        final StringBuilder text = new StringBuilder();
        final Map<Integer, ToolCallAcc> toolCalls = new LinkedHashMap<>();
        String finishReason;
        int promptTokens;
        int completionTokens;

        ToolCallAcc toolCall(int index) {
            return toolCalls.computeIfAbsent(index, i -> new ToolCallAcc());
        }

        /** Rebuild the synthetic {@code /chat/completions} response so the proven
         *  {@link #convertResponse} path produces the terminal aggregate. */
        Map<String, Object> toResponseMap() {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("content", text.length() > 0 ? text.toString() : null);
            if (!toolCalls.isEmpty()) {
                List<Map<String, Object>> tcs = new ArrayList<>();
                for (ToolCallAcc acc : toolCalls.values()) {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", acc.name);
                    fn.put("arguments", acc.arguments.toString());
                    Map<String, Object> tc = new LinkedHashMap<>();
                    tc.put("id", acc.id);
                    tc.put("type", "function");
                    tc.put("function", fn);
                    tcs.add(tc);
                }
                message.put("tool_calls", tcs);
            }
            // OpenAI reports finish_reason=tool_calls when the model chose tools; if the
            // stream ended without an explicit reason but tool calls accrued, treat it as
            // such so convertResponse normalises stopReason to "tool_use".
            String fr = finishReason;
            if ((fr == null || fr.isEmpty()) && !toolCalls.isEmpty()) {
                fr = "tool_calls";
            }
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("finish_reason", fr);
            choice.put("message", message);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("choices", List.of(choice));
            resp.put("usage", Map.of("prompt_tokens", promptTokens, "completion_tokens", completionTokens));
            return resp;
        }

        static final class ToolCallAcc {
            String id;
            String name;
            final StringBuilder arguments = new StringBuilder();
        }
    }
}
