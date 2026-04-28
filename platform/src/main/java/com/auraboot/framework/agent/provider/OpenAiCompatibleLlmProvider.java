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
        // Build OpenAI Chat Completions request
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
                    // Standard function tool
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", t.getName());
                    fn.put("description", t.getDescription());
                    fn.put("parameters", t.getInputSchema() != null ? t.getInputSchema()
                            : Map.of("type", "object", "properties", Map.of()));
                    tools.add(Map.<String, Object>of("type", "function", "function", fn));
                }
            }
            body.put("tools", tools);
        } else if (request.getTools() != null && !request.getTools().isEmpty()) {
            log.debug("Skipping tool payload for model '{}' because provider compatibility is disabled", request.getModel());
        }

        // Strip trailing /v1 or /v1/ from baseUrl to avoid double path segments
        String normalizedBase = baseUrl;
        if (normalizedBase.endsWith("/v1/")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 4);
        } else if (normalizedBase.endsWith("/v1")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 3);
        }

        String responseBody = webClient.post()
                .uri(normalizedBase + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("content-type", "application/json")
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        Map<String, Object> resp = objectMapper.readValue(responseBody, Map.class);
        return convertResponse(resp);
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
                            fn.put("name", bMap.get("name"));
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
    private LlmChatResponse convertResponse(Map<String, Object> resp) {
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

                content.add(LlmChatResponse.ContentBlock.builder()
                        .type("tool_use")
                        .id((String) tc.get("id"))
                        .name((String) fn.get("name"))
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
