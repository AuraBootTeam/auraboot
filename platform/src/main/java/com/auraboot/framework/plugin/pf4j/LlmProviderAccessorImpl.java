package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.plugin.extension.AiProviderAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bridges PF4J command handlers to platform-managed LLM providers.
 */
public class LlmProviderAccessorImpl implements AiProviderAccessor {

    private final LlmProviderFactory providerFactory;
    private final ObjectMapper objectMapper;
    private final Long tenantId;

    public LlmProviderAccessorImpl(LlmProviderFactory providerFactory, ObjectMapper objectMapper, Long tenantId) {
        this.providerFactory = providerFactory;
        this.objectMapper = objectMapper;
        this.tenantId = tenantId;
    }

    @Override
    public ChatResponse chat(ChatRequest request) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("AI provider chat request is required");
        }
        String providerCode = StringUtils.hasText(request.providerCode())
                ? request.providerCode().trim()
                : providerFactory.resolveProviderByModel(request.modelName());
        LlmProviderFactory.ProviderConfig config = providerFactory.resolveConfig(tenantId, providerCode);
        if (config == null || !StringUtils.hasText(config.getApiKey())) {
            throw new IllegalStateException("LLM provider is not configured for tenant " + tenantId);
        }
        String effectiveProviderCode = StringUtils.hasText(config.getProviderCode())
                ? config.getProviderCode()
                : providerCode;
        LlmProvider provider = providerFactory.getProvider(effectiveProviderCode);
        if (provider == null) {
            throw new IllegalStateException("LLM provider implementation is not available: " + effectiveProviderCode);
        }

        int maxTokens = request.maxTokens() > 0 ? request.maxTokens() : config.getMaxTokens();
        String modelName = StringUtils.hasText(request.modelName())
                ? request.modelName().trim()
                : config.getDefaultModel();
        LlmChatRequest llmRequest = LlmChatRequest.builder()
                .providerCode(effectiveProviderCode)
                .model(modelName)
                .systemPrompt(request.systemPrompt())
                .messages(toLlmMessages(request.messages()))
                .maxTokens(maxTokens)
                .build();
        LlmChatResponse response = provider.chat(llmRequest, config.getApiKey(), config.getBaseUrl());
        String text = extractText(response);
        return new ChatResponse(
                effectiveProviderCode,
                modelName,
                text,
                response == null ? 0 : response.getInputTokens(),
                response == null ? 0 : response.getOutputTokens(),
                response == null ? 0 : response.getInputTokens() + response.getOutputTokens(),
                response == null ? "{}" : objectMapper.writeValueAsString(response)
        );
    }

    private List<LlmChatRequest.Message> toLlmMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(message -> LlmChatRequest.Message.text(message.role(), message.content()))
                .collect(Collectors.toList());
    }

    private static String extractText(LlmChatResponse response) {
        if (response == null || response.getContent() == null) {
            return "";
        }
        return response.getContent().stream()
                .filter(block -> "text".equals(block.getType()))
                .map(LlmChatResponse.ContentBlock::getText)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));
    }
}
