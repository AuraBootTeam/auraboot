package com.auraboot.framework.agent.integration.meta;

import com.auraboot.framework.meta.ai.AiFieldGenerator;
import com.auraboot.framework.meta.ai.AiFieldProcessor.AiGenerationRequest;
import com.auraboot.framework.meta.ai.AiFieldProcessor.AiGenerationResult;
import com.auraboot.framework.meta.ai.AiFieldProcessor.ImageInput;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** Model adapter for metadata field generation. */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmAiFieldGenerator implements AiFieldGenerator {
    private final LlmProviderFactory llmProviderFactory;

    @Override
    public AiGenerationResult generate(AiGenerationRequest request) {
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
            String effectiveProviderCode = LlmProviderFactory.effectiveProviderCode(null, config);
            LlmProvider provider = llmProviderFactory.getProvider(effectiveProviderCode);

            // 2. Build prompt
            String prompt = buildPrompt(request);

            // 3. Build LlmChatRequest
            int maxTokens = request.getMaxTokens() != null ? request.getMaxTokens() : 500;

            // F.3 vision: when caller attaches images, materialise one user
            // message per image (image first + accompanying text on the last
            // image only, mirroring the chat path). Empty/null list keeps the
            // text-only path byte-identical with the pre-F.3 behaviour.
            List<ImageInput> images = request.getImages();
            List<LlmChatRequest.Message> messages;
            if (images != null && !images.isEmpty()) {
                messages = new java.util.ArrayList<>(images.size());
                for (int i = 0; i < images.size(); i++) {
                    ImageInput img = images.get(i);
                    if (img == null || img.getData() == null || img.getData().isBlank()) {
                        continue;
                    }
                    String mediaType = img.getMediaType() != null ? img.getMediaType() : "image/png";
                    // Attach prompt text only on the final image so the model
                    // sees [img1, img2, ..., imgN, text] — Anthropic best
                    // practice for multi-image OCR.
                    String textForBlock = (i == images.size() - 1) ? prompt : null;
                    messages.add(LlmChatRequest.Message.imageBase64(
                            "user", mediaType, img.getData(), textForBlock));
                }
                if (messages.isEmpty()) {
                    // All entries were null/blank — fall back to text path so
                    // callers still get a usable response instead of an empty
                    // message list (which Anthropic rejects).
                    messages = List.of(LlmChatRequest.Message.text("user", prompt));
                }
            } else {
                messages = List.of(LlmChatRequest.Message.text("user", prompt));
            }

            LlmChatRequest chatRequest = LlmChatRequest.builder()
                    .model(config.getDefaultModel())
                    .systemPrompt("You are a helpful assistant that generates structured data.")
                    .messages(messages)
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

}
