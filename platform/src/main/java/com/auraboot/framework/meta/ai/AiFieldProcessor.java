package com.auraboot.framework.meta.ai;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI Field Processor
 *
 * Handles AI-powered field generation operations:
 * - GENERATE: Generate text from a prompt template
 * - SUMMARIZE: Summarize content from source fields
 * - TRANSLATE: Translate content to target language
 * - CLASSIFY: Classify content into categories
 * - EXTRACT: Extract structured data from text
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiFieldProcessor {

    @Value("${ai.service.enabled:true}")
    private boolean aiEnabled;

    private final LlmProviderFactory llmProviderFactory;

    /**
     * Process an AI field operation
     *
     * @param request the AI generation request
     * @return generated content
     */
    public AiGenerationResult process(AiGenerationRequest request) {
        if (!aiEnabled) {
            return AiGenerationResult.builder()
                    .success(false)
                    .error("AI service is not enabled. Please configure ai.service.enabled=true")
                    .build();
        }

        try {
            // 1. Resolve LLM provider config for current tenant
            Long tenantId = MetaContext.getCurrentTenantId();
            LlmProviderFactory.ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, null);
            if (config == null) {
                return AiGenerationResult.builder()
                        .success(false)
                        .error("No LLM provider configured. Please configure an LLM provider in Cloud Config.")
                        .build();
            }
            LlmProvider provider = llmProviderFactory.getProvider(config.getProviderCode());

            // 2. Build prompt
            String prompt = buildPrompt(request);

            // 3. Build LlmChatRequest
            int maxTokens = request.getMaxTokens() != null ? request.getMaxTokens() : 500;
            LlmChatRequest chatRequest = LlmChatRequest.builder()
                    .model(config.getDefaultModel())
                    .systemPrompt("You are a helpful assistant that generates structured data.")
                    .messages(List.of(LlmChatRequest.Message.builder()
                            .role("user")
                            .content(prompt)
                            .build()))
                    .maxTokens(maxTokens)
                    .build();

            // 4. Call LLM provider
            LlmChatResponse response = provider.chat(chatRequest, config.getApiKey(), config.getBaseUrl());

            // 5. Extract text content from response
            String content = extractTextContent(response);
            if (content == null || content.isBlank()) {
                return AiGenerationResult.builder()
                        .success(false)
                        .error("Empty response from LLM provider")
                        .build();
            }

            int totalTokens = response.getInputTokens() + response.getOutputTokens();
            return AiGenerationResult.builder()
                    .success(true)
                    .content(content)
                    .tokensUsed(totalTokens > 0 ? totalTokens : null)
                    .build();

        } catch (Exception e) {
            log.error("AI field processing failed: {}", e.getMessage(), e);
            return AiGenerationResult.builder()
                    .success(false)
                    .error("AI service error: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Extract the first text content block from an LLM response.
     */
    private String extractTextContent(LlmChatResponse response) {
        if (response == null || response.getContent() == null) return null;
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                return block.getText();
            }
        }
        return null;
    }

    /**
     * Build the full prompt from request parameters
     */
    private String buildPrompt(AiGenerationRequest request) {
        StringBuilder sb = new StringBuilder();

        switch (request.getOperation()) {
            case "summarize":
                sb.append("Summarize the following content concisely:\n\n");
                break;
            case "translate":
                sb.append("Translate the following content to ")
                        .append(request.getTargetLanguage() != null ? request.getTargetLanguage() : "English")
                        .append(":\n\n");
                break;
            case "classify":
                sb.append("Classify the following content into one of these categories: ")
                        .append(String.join(", ", request.getCategories() != null ? request.getCategories() : List.of()))
                        .append(":\n\n");
                break;
            case "extract":
                sb.append("Extract the following fields from the content: ")
                        .append(request.getExtractFields() != null ? String.join(", ", request.getExtractFields()) : "")
                        .append(":\n\n");
                break;
            default: // GENERATE
                if (request.getPrompt() != null && !request.getPrompt().isEmpty()) {
                    sb.append(request.getPrompt()).append("\n\n");
                }
                break;
        }

        // Append source content from fields
        if (request.getSourceContent() != null) {
            for (Map.Entry<String, String> entry : request.getSourceContent().entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        return sb.toString().trim();
    }

    /**
     * AI generation request
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiGenerationRequest {
        /** Operation type: GENERATE, SUMMARIZE, TRANSLATE, CLASSIFY, EXTRACT */
        private String operation;
        /** Custom prompt template */
        private String prompt;
        /** Source field contents (fieldCode -> value) */
        private Map<String, String> sourceContent;
        /** Target language for TRANSLATE operation */
        private String targetLanguage;
        /** Categories for CLASSIFY operation */
        private List<String> categories;
        /** Fields to extract for EXTRACT operation */
        private List<String> extractFields;
        /** Max tokens for generation */
        private Integer maxTokens;
        /** Temperature for generation (0.0 - 1.0) */
        private Double temperature;
    }

    /**
     * AI generation result
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiGenerationResult {
        private boolean success;
        private String content;
        private String error;
        private Integer tokensUsed;
    }
}
