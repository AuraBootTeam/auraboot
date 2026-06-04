package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;

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
 * <p>The stub answers normal calls with a single text block containing
 * {@code "[stub response]"} and stop_reason {@code "end_turn"}. When a test
 * turn has just returned a {@code tool_result}, the final text includes a
 * compact deterministic digest of that result so browser E2E can assert the
 * real tool loop outcome without a real LLM summarizer.
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

    /**
     * Explicit E2E-only directive. When the latest user message contains this
     * marker followed by a JSON object `{id,name,input}`, the stub returns one
     * deterministic tool_use block. The marker is intentionally noisy so normal
     * development prompts cannot trigger tool execution accidentally.
     */
    public static final String TOOL_USE_MARKER = "@@AURABOOT_STUB_TOOL_USE@@";

    /**
     * E2E-only directive. When the latest user message text contains this
     * marker, the stub delays its synchronous {@link #chat} response (and slows
     * {@link #streamChat}) by a bounded duration. The marker may be followed by
     * {@code =<millis>} to override the default; the value is clamped to
     * {@code [0, }{@link #MAX_STUB_DELAY_MS}{@code ]}.
     *
     * <p>Why this exists: the group-agent named-agent turn runs the synchronous
     * tool loop ({@code ChatTurnRuntime.runToolLoop -> callProvider -> chat()}),
     * so the {@code delayElements} slow-stream path in {@link #streamChat} never
     * applies to it. With an instant stub reply, {@code ai_turn_started} and
     * {@code ai_turn_completed} fire back-to-back and the iOS/Android typing
     * bubble flashes too fast for a UI test to observe. A bounded delay inside
     * {@code chat()} keeps the turn observably in-flight so the bubble lifecycle
     * (started -> ... -> completed -> removed) can be asserted. Test affordance
     * only — it never fires unless an E2E message explicitly carries the marker.
     */
    public static final String DELAY_MARKER = "@@AURABOOT_STUB_DELAY@@";

    /** Default in-flight delay applied when {@link #DELAY_MARKER} has no explicit {@code =millis}. */
    static final long DEFAULT_STUB_DELAY_MS = 2500L;

    /** Upper bound for the stub reply delay; parsed overrides are clamped to this. */
    static final long MAX_STUB_DELAY_MS = 10_000L;

    /** Fixed response text returned for every chat request. */
    static final String STUB_RESPONSE_TEXT = "[stub response]";

    private static final int MAX_TOOL_RESULT_DIGEST_CHARS = 2000;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        // E2E observability: keep the turn in-flight long enough for a UI test to
        // observe the typing-bubble lifecycle. No-op unless the request carries
        // DELAY_MARKER. onTurnBegin has already fired before chat() is invoked,
        // so the bubble is visible for the duration of this sleep.
        applyStubDelay(request);
        LlmChatResponse scripted = scriptedToolUseResponse(request);
        if (scripted != null) {
            return scripted;
        }
        return buildStubResponse(request);
    }

    @Override
    public Flux<LlmChunk> streamChat(LlmChatRequest request, String apiKey, String baseUrl) {
        LlmChatResponse scripted = scriptedToolUseResponse(request);
        if (scripted != null) {
            return Flux.just(LlmChunk.done(0L, scripted));
        }
        LlmChatResponse aggregate = buildStubResponse(request);
        // Emit one delta chunk + one terminal done chunk so downstream
        // aggregators that expect at least one delta before the terminal frame
        // (mirroring Anthropic's wire shape) stay byte-compatible.
        LlmChunk deltaChunk = LlmChunk.delta(0L, STUB_RESPONSE_TEXT);
        LlmChunk doneChunk = LlmChunk.done(1L, aggregate);
        Flux<LlmChunk> stream = Flux.just(deltaChunk, doneChunk);
        // DELAY_MARKER spreads the two chunks across the requested window so any
        // streaming caller (SSE bridge, LlmCallExecutor) sees a slow turn too.
        long delayMs = resolveStubDelayMs(request);
        if (delayMs > 0) {
            return stream.delayElements(Duration.ofMillis(Math.max(1L, delayMs / 2)));
        }
        return shouldSlowVisualStream(request)
                ? stream.delayElements(Duration.ofMillis(1500))
                : stream;
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
        String responseText = latestToolResultDigest(request);
        if (responseText == null || responseText.isBlank()) {
            responseText = STUB_RESPONSE_TEXT;
        }
        List<LlmChatResponse.ContentBlock> content = new ArrayList<>(1);
        content.add(LlmChatResponse.ContentBlock.builder()
                .type("text")
                .text(responseText)
                .build());
        int inputTokens = estimateInputTokens(request);
        int outputTokens = Math.max(1, responseText.length() / 4);
        return LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(content)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cacheCreationInputTokens(0)
                .cacheReadInputTokens(0)
                .build();
    }

    private String latestToolResultDigest(LlmChatRequest request) {
        Object result = latestToolResult(request);
        if (result == null) {
            return null;
        }
        Object normalized = normalizeToolResult(result);
        String json;
        try {
            json = OBJECT_MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            json = String.valueOf(normalized);
        }
        return STUB_RESPONSE_TEXT + "\n" + truncate(json, MAX_TOOL_RESULT_DIGEST_CHARS);
    }

    private Object latestToolResult(LlmChatRequest request) {
        if (request == null || request.getMessages() == null) {
            return null;
        }
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            LlmChatRequest.Message message = request.getMessages().get(i);
            if (message == null || message.getContent() == null) continue;
            Object content = message.getContent();
            if (!(content instanceof List<?> list)) continue;
            for (int j = list.size() - 1; j >= 0; j--) {
                Object item = list.get(j);
                Object result = toolResultPayload(item);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private Object toolResultPayload(Object item) {
        if (item instanceof LlmChatRequest.ContentBlock block
                && "tool_result".equals(block.getType())) {
            return block.getResult();
        }
        if (item instanceof Map<?, ?> map
                && "tool_result".equals(String.valueOf(map.get("type")))) {
            Object result = map.get("result");
            return result != null ? result : map.get("content");
        }
        return null;
    }

    private Object normalizeToolResult(Object result) {
        if (result instanceof String s) {
            String trimmed = s.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                try {
                    return OBJECT_MAPPER.readValue(trimmed, Object.class);
                } catch (Exception ignored) {
                    return trimmed;
                }
            }
            return trimmed;
        }
        return result;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private LlmChatResponse scriptedToolUseResponse(LlmChatRequest request) {
        if (request == null || request.getMessages() == null) {
            return null;
        }
        ToolUseDirective directive = latestToolUseDirective(request);
        if (directive == null || hasToolResultAfter(request, directive.messageIndex())) {
            return null;
        }
        String json = directive.json();
        if (json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> payload = OBJECT_MAPPER.readValue(
                    json, new TypeReference<Map<String, Object>>() {});
            Object name = payload.get("name");
            if (name == null || String.valueOf(name).isBlank()) {
                return null;
            }
            Object input = payload.get("input");
            String id = payload.get("id") == null
                    ? "toolu-stub"
                    : String.valueOf(payload.get("id"));
            LlmChatResponse.ContentBlock toolUse = LlmChatResponse.ContentBlock.builder()
                    .type("tool_use")
                    .id(id)
                    .name(String.valueOf(name))
                    .input(toStringObjectMap(input))
                    .build();
            return LlmChatResponse.builder()
                    .stopReason("tool_use")
                    .content(List.of(toolUse))
                    .inputTokens(estimateInputTokens(request))
                    .outputTokens(1)
                    .build();
        } catch (Exception e) {
            log.warn("Ignoring malformed stub tool_use directive: {}", e.getMessage());
            return null;
        }
    }

    private ToolUseDirective latestToolUseDirective(LlmChatRequest request) {
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            LlmChatRequest.Message message = request.getMessages().get(i);
            if (message == null || !"user".equals(message.getRole())) continue;
            String text = userText(message);
            if (text == null) continue;
            int marker = text.indexOf(TOOL_USE_MARKER);
            if (marker >= 0) {
                return new ToolUseDirective(
                        i,
                        text.substring(marker + TOOL_USE_MARKER.length()).trim());
            }
        }
        return null;
    }

    private boolean hasToolResultAfter(LlmChatRequest request, int messageIndex) {
        for (int i = messageIndex + 1; i < request.getMessages().size(); i++) {
            LlmChatRequest.Message message = request.getMessages().get(i);
            if (message == null || message.getContent() == null) continue;
            Object content = message.getContent();
            if (content instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof LlmChatRequest.ContentBlock block
                            && "tool_result".equals(block.getType())) {
                        return true;
                    }
                    if (item instanceof Map<?, ?> map
                            && "tool_result".equals(String.valueOf(map.get("type")))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Map<String, Object> toStringObjectMap(Object input) {
        if (!(input instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private String userText(LlmChatRequest.Message message) {
        Object content = message.getContent();
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> list) {
            StringBuilder joined = new StringBuilder();
            for (Object item : list) {
                if (item instanceof LlmChatRequest.ContentBlock block && block.getText() != null) {
                    joined.append(block.getText());
                } else if (item instanceof LlmChatRequest.MessageContentBlock block && block.getText() != null) {
                    joined.append(block.getText());
                } else if (item instanceof Map<?, ?> map && map.get("text") != null) {
                    joined.append(map.get("text"));
                }
            }
            if (!joined.isEmpty()) {
                return joined.toString();
            }
        }
        return null;
    }

    private boolean shouldSlowVisualStream(LlmChatRequest request) {
        String text = latestUserText(request);
        return text != null && text.contains("WS001 visual stream");
    }

    /** Text of the most recent {@code user}-role message, or null when absent. */
    private String latestUserText(LlmChatRequest request) {
        if (request == null || request.getMessages() == null) {
            return null;
        }
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            LlmChatRequest.Message message = request.getMessages().get(i);
            if (message == null || !"user".equals(message.getRole())) continue;
            String text = userText(message);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /**
     * Resolve the bounded in-flight delay (ms) requested via {@link #DELAY_MARKER}
     * in the latest user message. Returns 0 when the marker is absent. An explicit
     * {@code =<millis>} suffix overrides {@link #DEFAULT_STUB_DELAY_MS}; the result
     * is clamped to {@code [0, }{@link #MAX_STUB_DELAY_MS}{@code ]}.
     */
    long resolveStubDelayMs(LlmChatRequest request) {
        String text = latestUserText(request);
        if (text == null) {
            return 0L;
        }
        int marker = text.indexOf(DELAY_MARKER);
        if (marker < 0) {
            return 0L;
        }
        long delay = DEFAULT_STUB_DELAY_MS;
        int afterMarker = marker + DELAY_MARKER.length();
        if (afterMarker < text.length() && text.charAt(afterMarker) == '=') {
            int j = afterMarker + 1;
            StringBuilder digits = new StringBuilder();
            while (j < text.length() && Character.isDigit(text.charAt(j))) {
                digits.append(text.charAt(j));
                j++;
            }
            if (!digits.isEmpty()) {
                try {
                    delay = Long.parseLong(digits.toString());
                } catch (NumberFormatException ignored) {
                    delay = DEFAULT_STUB_DELAY_MS;
                }
            }
        }
        return Math.max(0L, Math.min(delay, MAX_STUB_DELAY_MS));
    }

    /** Block the calling (async turn) thread for the requested {@link #DELAY_MARKER} window. */
    private void applyStubDelay(LlmChatRequest request) {
        long delayMs = resolveStubDelayMs(request);
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ToolUseDirective(int messageIndex, String json) {}

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
