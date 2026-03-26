package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.AnthropicRequest;
import com.auraboot.framework.agent.dto.AnthropicResponse;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude Messages API provider.
 * Translates unified LlmChatRequest into Anthropic's /v1/messages format.
 */
@Slf4j
@Component
public class AnthropicLlmProvider implements LlmProvider {

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
        // Build Anthropic-specific request
        AnthropicRequest anthropicReq = AnthropicRequest.builder()
                .model(request.getModel())
                .max_tokens(request.getMaxTokens())
                .system(request.getSystemPrompt())
                .messages(convertMessages(request.getMessages()))
                .tools(convertTools(request.getTools()))
                .build();

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
        return convertResponse(anthropicResp);
    }

    @Override
    public double estimateCost(String model, int inputTokens, int outputTokens) {
        double inputRate;
        double outputRate;
        if (model != null && model.contains("opus")) {
            inputRate = 15.0; outputRate = 75.0;
        } else if (model != null && model.contains("haiku")) {
            inputRate = 0.25; outputRate = 1.25;
        } else {
            // sonnet default
            inputRate = 3.0; outputRate = 15.0;
        }
        return (inputTokens * inputRate + outputTokens * outputRate) / 1_000_000.0;
    }

    // =========================================================================
    // Format conversion: Unified ↔ Anthropic
    // =========================================================================

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
        return tools.stream()
                .map(t -> AnthropicRequest.Tool.builder()
                        .name(t.getName())
                        .description(t.getDescription())
                        .input_schema(t.getInputSchema())
                        .build())
                .toList();
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
                        .build());
            }
        }
        return LlmChatResponse.builder()
                .stopReason(resp.getStop_reason())
                .content(blocks)
                .inputTokens(resp.getUsage() != null ? resp.getUsage().getInput_tokens() : 0)
                .outputTokens(resp.getUsage() != null ? resp.getUsage().getOutput_tokens() : 0)
                .build();
    }
}
