package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

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

        // P1 vision: this provider is the OpenAI-compat fall-through used by
        // DeepSeek / Qwen / Zhipu / MiniMax / etc. Most of them don't accept
        // OpenAI's image_url content blocks yet, and the few that do have
        // diverging schemas (qwen-vl uses a different field shape). Until P1.5
        // adds a per-provider vision matrix, we refuse image input outright
        // rather than silently dropping it or fabricating "[image]" text —
        // either alternative would erase the user's intent.
        if (containsImageContent(request.getMessages())) {
            throw new IllegalArgumentException(
                    "openai-compatible provider does not support vision in this build; "
                            + "use Anthropic (Claude 3.5+) for image input.");
        }

        Map<String, Object> body = buildOpenAiRequestBody(request);

        // Strip trailing /v1 or /v1/ from baseUrl to avoid double path segments
        String normalizedBase = baseUrl;
        if (normalizedBase.endsWith("/v1/")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 4);
        } else if (normalizedBase.endsWith("/v1")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 3);
        }

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

        // Convert unified messages to OpenAI format
        if (request.getMessages() != null) {
            for (LlmChatRequest.Message msg : request.getMessages()) {
                messages.add(convertMessageToOpenAi(msg));
            }
        }
        body.put("messages", messages);

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

            // User message with tool_result blocks → multiple tool messages
            // OpenAI expects separate messages per tool result
            // Return the first tool result; caller handles multi-tool via message splitting
            for (Object block : blocks) {
                Map<String, Object> bMap = toBlockMap(block);
                if (bMap != null) {
                    String type = (String) bMap.get("type");
                    if ("tool_result".equals(type)) {
                        result.put("role", "tool");
                        Object toolUseId = bMap.get("tool_use_id") != null ? bMap.get("tool_use_id") : bMap.get("toolUseId");
                        result.put("tool_call_id", toolUseId);
                        Object toolContent = bMap.get("content") != null ? bMap.get("content") : bMap.get("result");
                        result.put("content", serializeToolContent(toolContent));
                        return result;
                    }
                }
            }
        }

        result.put("content", String.valueOf(content));
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
}
