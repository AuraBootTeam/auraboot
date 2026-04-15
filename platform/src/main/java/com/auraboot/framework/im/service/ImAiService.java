package com.auraboot.framework.im.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.pubsub.ImRedisPubSub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles @AI mentions in IM conversations.
 * When a user mentions "ai" in a message, this service calls the configured
 * LLM provider and posts the AI response back to the same conversation.
 *
 * @since 6.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImAiService {

    private final LlmProviderFactory llmProviderFactory;
    private final ImMessageService messageService;
    private final ImRedisPubSub redisPubSub;
    private final ImConversationMemberMapper memberMapper;

    private static final String AI_SYSTEM_PROMPT = """
            You are AuraBot, an AI assistant embedded in AuraBoot's IM system.
            You help users with business questions, data analysis, and general inquiries.
            Keep responses concise and helpful. Use markdown formatting when appropriate.
            Respond in the same language as the user's message.
            """;

    /**
     * Check if a message mentions AI.
     */
    public boolean hasMention(ImMessage message) {
        if (message.getMentions() == null) return false;
        return message.getMentions().toLowerCase().contains("\"ai\"");
    }

    /**
     * Generate AI response asynchronously and post it back to the conversation.
     */
    @Async("eventTaskExecutor")
    public void generateResponse(ImMessage userMessage, Long tenantId) {
        MetaContext.setContext(tenantId, 0L, null, null);
        try {
            // Resolve LLM provider
            LlmProviderFactory.ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, null);
            LlmProvider provider = llmProviderFactory.getProvider(config.getProviderCode());

            // Build conversation context from recent messages
            List<LlmChatRequest.Message> messages = buildConversationContext(userMessage, tenantId);

            // Build request
            LlmChatRequest request = LlmChatRequest.builder()
                    .model(config.getDefaultModel())
                    .systemPrompt(AI_SYSTEM_PROMPT)
                    .maxTokens(1024)
                    .messages(messages)
                    .build();

            // Call LLM
            LlmChatResponse response = provider.chat(request, config.getApiKey(), config.getBaseUrl());

            // Extract text response
            String aiText = response.getContent().stream()
                    .filter(b -> "text".equals(b.getType()))
                    .map(LlmChatResponse.ContentBlock::getText)
                    .findFirst()
                    .orElse("Sorry, I could not generate a response.");

            // Post AI response as system message in the same conversation
            String clientMsgId = "ai_reply_" + userMessage.getId() + "_" + UUID.randomUUID().toString().substring(0, 8);
            ImMessage aiMessage = messageService.sendSystemMessage(
                    userMessage.getConversationId(), tenantId,
                    "ai_response", aiText, null, clientMsgId);

            // Push to all conversation members via WebSocket
            pushToConversationMembers(userMessage.getConversationId(), aiMessage, tenantId);

            log.debug("AI response delivered: conversationId={}, seq={}, tokens={}/{}",
                    userMessage.getConversationId(), aiMessage.getSeq(),
                    response.getInputTokens(), response.getOutputTokens());

        } catch (Exception e) {
            log.error("Failed to generate AI response for message={}: {}",
                    userMessage.getId(), e.getMessage(), e);

            // Post error message so user knows something went wrong
            try {
                String errorMsg = "Sorry, I encountered an error processing your request. Please try again later.";
                String clientMsgId = "ai_err_" + userMessage.getId();
                ImMessage errorMessage = messageService.sendSystemMessage(
                        userMessage.getConversationId(), tenantId,
                        "ai_response", errorMsg, null, clientMsgId);
                pushToConversationMembers(userMessage.getConversationId(), errorMessage, tenantId);
            } catch (Exception ex) {
                log.error("Failed to send AI error message", ex);
            }
        } finally {
            MetaContext.clear();
        }
    }

    /**
     * Build conversation context from recent messages (up to 10) before the current message.
     * Maps user messages to "user" role and AI_RESPONSE messages to "assistant" role.
     */
    private List<LlmChatRequest.Message> buildConversationContext(ImMessage currentMessage, Long tenantId) {
        List<LlmChatRequest.Message> messages = new ArrayList<>();

        // Fetch recent messages before the current one (for context)
        List<ImMessage> recentMessages = messageService.getMessagesBeforeSeq(
                currentMessage.getConversationId(), currentMessage.getSeq(), 10, tenantId);

        // recentMessages is ordered by seq DESC, reverse for chronological order
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            ImMessage msg = recentMessages.get(i);
            if (msg.getRecalled() != null && msg.getRecalled()) continue;
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;

            String role = "ai_response".equals(msg.getMessageType()) ? "assistant" : "user";
            messages.add(LlmChatRequest.Message.builder()
                    .role(role)
                    .content(msg.getContent())
                    .build());
        }

        // Add the current message
        messages.add(LlmChatRequest.Message.builder()
                .role("user")
                .content(currentMessage.getContent())
                .build());

        return messages;
    }

    private void pushToConversationMembers(Long conversationId, ImMessage message, Long tenantId) {
        WsFrame frame = WsFrame.builder()
                .type("message")
                .data(Map.of(
                        "messageId", message.getId(),
                        "conversationId", conversationId,
                        "senderId", message.getSenderId(),
                        "seq", message.getSeq(),
                        "messageType", message.getMessageType(),
                        "content", message.getContent() != null ? message.getContent() : "",
                        "createdAt", message.getCreatedAt().toString()
                ))
                .build();

        List<Long> memberIds = memberMapper.findHumanMemberIds(conversationId, tenantId);
        redisPubSub.publish(memberIds, frame);
    }
}
