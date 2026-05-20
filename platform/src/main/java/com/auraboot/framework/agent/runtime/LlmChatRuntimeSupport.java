package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;

import java.util.List;
import java.util.Map;

/**
 * Shared helpers for LLM chat runtime tracing and tool-call validation.
 */
public final class LlmChatRuntimeSupport {

    private LlmChatRuntimeSupport() {
    }

    public static Map<String, Object> buildGenerationSpanInput(LlmChatRequest request) {
        if (request == null) {
            return Map.of();
        }
        return Map.of(
                "model", request.getModel(),
                "system_prompt", request.getSystemPrompt(),
                "messages", request.getMessages() != null ? request.getMessages() : List.of(),
                "tools", request.getTools() != null ? request.getTools() : List.of(),
                "max_tokens", request.getMaxTokens()
        );
    }

    public static Map<String, Object> buildGenerationSpanOutput(LlmChatResponse response) {
        if (response == null) {
            return Map.of();
        }
        return Map.of(
                "stop_reason", response.getStopReason(),
                "content", response.getContent() != null ? response.getContent() : List.of(),
                "input_tokens", response.getInputTokens(),
                "output_tokens", response.getOutputTokens()
        );
    }

    public static boolean isToolOffered(List<LlmChatRequest.Tool> tools, String toolName) {
        if (tools == null || tools.isEmpty() || toolName == null || toolName.isBlank()) {
            return false;
        }
        return tools.stream()
                .map(LlmChatRequest.Tool::getName)
                .anyMatch(toolName::equals);
    }
}
