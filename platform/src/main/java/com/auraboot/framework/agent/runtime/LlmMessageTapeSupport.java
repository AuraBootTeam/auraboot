package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.util.ReasoningTagSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Shared helpers for the LLM chat message tape used by chat turns and resumable tool continuations.
 */
public final class LlmMessageTapeSupport {

    private LlmMessageTapeSupport() {
    }

    public static LlmChatRequest.Message buildAssistantMessage(List<LlmChatResponse.ContentBlock> responseBlocks) {
        List<LlmChatRequest.ContentBlock> blocks = new ArrayList<>();
        if (responseBlocks != null) {
            for (LlmChatResponse.ContentBlock responseBlock : responseBlocks) {
                if (responseBlock == null) {
                    continue;
                }
                LlmChatRequest.ContentBlock block = new LlmChatRequest.ContentBlock();
                block.setType(responseBlock.getType());
                if ("text".equals(responseBlock.getType())) {
                    block.setText(responseBlock.getText());
                } else if ("tool_use".equals(responseBlock.getType())) {
                    block.setId(responseBlock.getId());
                    block.setName(responseBlock.getName());
                    block.setInput(responseBlock.getInput());
                }
                blocks.add(block);
            }
        }
        return LlmChatRequest.Message.builder()
                .role("assistant")
                .content(blocks)
                .build();
    }

    public static LlmChatRequest.ContentBlock buildToolResultBlock(ObjectMapper objectMapper,
                                                                   String toolUseId,
                                                                   Map<String, Object> result) {
        LlmChatRequest.ContentBlock block = new LlmChatRequest.ContentBlock();
        block.setType("tool_result");
        block.setToolUseId(toolUseId);
        try {
            block.setResult(objectMapper != null ? objectMapper.writeValueAsString(result) : String.valueOf(result));
        } catch (Exception e) {
            block.setResult(String.valueOf(result));
        }
        return block;
    }

    public static LlmChatRequest.Message buildToolResultMessage(List<LlmChatRequest.ContentBlock> toolResults) {
        return LlmChatRequest.Message.builder()
                .role("user")
                .content(toolResults != null ? toolResults : List.of())
                .build();
    }

    public static List<Map<String, Object>> serializeMessages(List<LlmChatRequest.Message> messages) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        if (messages == null) {
            return serialized;
        }
        for (LlmChatRequest.Message message : messages) {
            if (message == null) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", message.getRole());
            map.put("content", message.getContent());
            serialized.add(map);
        }
        return serialized;
    }

    public static List<LlmChatRequest.Message> deserializeMessages(List<Map<String, Object>> serialized) {
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        if (serialized == null) {
            return messages;
        }
        for (Map<String, Object> map : serialized) {
            if (map == null) {
                continue;
            }
            LlmChatRequest.Message message = new LlmChatRequest.Message();
            message.setRole((String) map.get("role"));
            message.setContent(map.get("content"));
            messages.add(message);
        }
        return messages;
    }

    public static <T> List<LlmChatRequest.Message> buildTextMessages(List<T> history,
                                                                     Function<T, String> roleExtractor,
                                                                     Function<T, String> contentExtractor,
                                                                     String userMessage) {
        return restoreOrBuildTextMessages(List.of(), history, roleExtractor, contentExtractor, userMessage);
    }

    public static <T> List<LlmChatRequest.Message> restoreOrBuildTextMessages(List<Map<String, Object>> stored,
                                                                              List<T> history,
                                                                              Function<T, String> roleExtractor,
                                                                              Function<T, String> contentExtractor,
                                                                              String userMessage) {
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        if (stored != null && !stored.isEmpty()) {
            messages.addAll(deserializeMessages(stored));
        } else if (history != null) {
            for (T message : history) {
                if (message == null) {
                    continue;
                }
                String role = roleExtractor.apply(message);
                if ("system".equals(role)) {
                    continue;
                }
                messages.add(LlmChatRequest.Message.text(role, contentExtractor.apply(message)));
            }
        }
        messages.add(LlmChatRequest.Message.text("user", userMessage != null ? userMessage : ""));
        return messages;
    }

    public static String extractTextFromResponse(LlmChatResponse response) {
        if (response == null || response.getContent() == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if (block != null && "text".equals(block.getType()) && block.getText() != null) {
                text.append(block.getText());
            }
        }
        if (text.isEmpty()) {
            return null;
        }
        String raw = text.toString();
        String cleaned = ReasoningTagSanitizer.stripComplete(raw).trim();
        return cleaned.isBlank() ? raw.trim() : cleaned;
    }
}
