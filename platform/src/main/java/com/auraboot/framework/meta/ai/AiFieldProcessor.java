package com.auraboot.framework.meta.ai;

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

    @org.springframework.lang.Nullable
    private final AiFieldGenerator generator;

    /**
     * Process an AI field operation
     *
     * @param request the AI generation request
     * @return generated content
     */
    public AiGenerationResult process(AiGenerationRequest request) {
        if (!aiEnabled) {
            return AiGenerationResult.builder().success(false)
                    .error("AI service is not enabled. Please configure ai.service.enabled=true").build();
        }
        if (generator == null) {
            return AiGenerationResult.builder().success(false)
                    .error("AI field generation is not installed").build();
        }
        return generator.generate(request);
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
        /**
         * F.3 vision: optional inline image attachments (base64-encoded). When
         * non-empty the processor switches to multi-modal message construction
         * and the underlying provider must support vision (Anthropic only at
         * present — OpenAI-compat throws explicitly).
         */
        private List<ImageInput> images;
    }

    /**
     * Inline image attachment for AI field vision input. {@code data} is raw
     * base64 (NO {@code data:} URI prefix — strip it on the client).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageInput {
        /** MIME type: image/jpeg, image/png, image/gif, image/webp */
        private String mediaType;
        /** Raw base64-encoded bytes (no data: prefix) */
        private String data;
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
